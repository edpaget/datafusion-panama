use std::ffi::{c_char, c_void, CStr};

use datafusion::execution::context::SessionContext;

use crate::dataframe::DFDataFrame;
use crate::result::{ffi_result, DFResult};
use crate::runtime::DFRuntime;

pub struct DFSession {
    pub(crate) context: SessionContext,
}

/// Creates a new `SessionContext` wrapped in a `DFResult`.
///
/// # Safety
/// `runtime` must be a valid pointer returned by `runtime_new`, or null (which returns an error).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn session_new(runtime: *mut DFRuntime) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        // Dereference runtime to validate the pointer; SessionContext::new() is synchronous.
        let _rt = unsafe { &*runtime };
        let context = SessionContext::new();
        let session = DFSession { context };
        let ptr = Box::into_raw(Box::new(session)) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Frees a `DFSession` previously returned by `session_new`.
///
/// # Safety
/// `session` must be a valid pointer returned by `session_new`, or null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn session_free(session: *mut DFSession) {
    if !session.is_null() {
        drop(unsafe { Box::from_raw(session) });
    }
}

/// Executes a SQL query and returns an opaque `DFDataFrame` pointer.
///
/// # Safety
/// - `runtime` must be a valid pointer returned by `runtime_new`.
/// - `session` must be a valid pointer returned by `session_new`.
/// - `sql` must be a valid, null-terminated C string pointer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn session_sql(
    runtime: *mut DFRuntime,
    session: *mut DFSession,
    sql: *const c_char,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!session.is_null(), "session pointer must not be null");
        assert!(!sql.is_null(), "sql pointer must not be null");

        let rt = unsafe { &*runtime };
        let sess = unsafe { &*session };
        let sql_str = unsafe { CStr::from_ptr(sql) }
            .to_str()
            .map_err(|e| -> Box<dyn std::error::Error> { Box::new(e) })?;

        let df = rt.runtime.block_on(sess.context.sql(sql_str))?;
        let dataframe = DFDataFrame { dataframe: df };
        let ptr = Box::into_raw(Box::new(dataframe)) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{result_error_message, result_free, result_is_ok, result_unwrap};
    use crate::runtime::{runtime_free, runtime_new};
    use std::ffi::CString;

    #[test]
    fn session_create_and_free() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        assert!(unsafe { result_is_ok(sess_result) });
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        assert!(!sess_ptr.is_null());
        unsafe { result_free(sess_result) };

        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn session_free_with_null_is_safe() {
        unsafe { session_free(std::ptr::null_mut()) };
    }

    #[test]
    fn session_sql_valid_query_returns_dataframe() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let sql = CString::new("SELECT 1 + 1 AS result").unwrap();
        let df_result = unsafe { session_sql(rt_ptr, sess_ptr, sql.as_ptr()) };
        assert!(unsafe { result_is_ok(df_result) });
        let df_ptr = unsafe { result_unwrap(df_result) } as *mut DFDataFrame;
        assert!(!df_ptr.is_null());
        unsafe { result_free(df_result) };

        unsafe { crate::dataframe::dataframe_free(df_ptr) };
        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn session_sql_invalid_query_returns_error() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let sql = CString::new("NOT VALID SQL AT ALL %%%").unwrap();
        let df_result = unsafe { session_sql(rt_ptr, sess_ptr, sql.as_ptr()) };
        assert!(!unsafe { result_is_ok(df_result) });
        let msg_ptr = unsafe { result_error_message(df_result) };
        assert!(!msg_ptr.is_null());
        unsafe { result_free(df_result) };

        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn session_sql_null_runtime_returns_error() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let sql = CString::new("SELECT 1").unwrap();
        let df_result = unsafe { session_sql(std::ptr::null_mut(), sess_ptr, sql.as_ptr()) };
        assert!(!unsafe { result_is_ok(df_result) });
        unsafe { result_free(df_result) };

        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn session_holds_valid_context() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        // Verify the context is functional by executing a trivial SQL query
        let session = unsafe { &*sess_ptr };
        let rt = unsafe { &*rt_ptr };
        let df = rt
            .runtime
            .block_on(session.context.sql("SELECT 1"))
            .expect("trivial SQL should succeed");
        let batches = rt
            .runtime
            .block_on(df.collect())
            .expect("collect should succeed");
        assert_eq!(batches.len(), 1);

        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }
}
