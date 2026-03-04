use std::ffi::{c_char, c_void, CStr};
use std::ptr::null_mut;

use datafusion::datasource::file_format::options::CsvReadOptions;
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

/// Registers a CSV file as a table in the session.
///
/// # Safety
/// - `runtime` must be a valid pointer returned by `runtime_new`.
/// - `session` must be a valid pointer returned by `session_new`.
/// - `table_name` must be a valid, null-terminated C string pointer.
/// - `path` must be a valid, null-terminated C string pointer to a file path.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn session_register_csv(
    runtime: *mut DFRuntime,
    session: *mut DFSession,
    table_name: *const c_char,
    path: *const c_char,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!session.is_null(), "session pointer must not be null");
        assert!(!table_name.is_null(), "table_name pointer must not be null");
        assert!(!path.is_null(), "path pointer must not be null");

        let rt = unsafe { &*runtime };
        let sess = unsafe { &*session };
        let name_str = unsafe { CStr::from_ptr(table_name) }
            .to_str()
            .map_err(|e| -> Box<dyn std::error::Error> { Box::new(e) })?;
        let path_str = unsafe { CStr::from_ptr(path) }
            .to_str()
            .map_err(|e| -> Box<dyn std::error::Error> { Box::new(e) })?;

        rt.runtime.block_on(sess.context.register_csv(
            name_str,
            path_str,
            CsvReadOptions::default(),
        ))?;

        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(null_mut());
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

    #[test]
    fn session_register_csv_and_query() {
        use std::io::Write;

        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        // Write a temp CSV file
        let dir = tempfile::tempdir().unwrap();
        let csv_path = dir.path().join("test.csv");
        let mut file = std::fs::File::create(&csv_path).unwrap();
        writeln!(file, "id,name").unwrap();
        writeln!(file, "1,alice").unwrap();
        writeln!(file, "2,bob").unwrap();
        drop(file);

        let table_name = CString::new("test_table").unwrap();
        let path = CString::new(csv_path.to_str().unwrap()).unwrap();
        let reg_result =
            unsafe { session_register_csv(rt_ptr, sess_ptr, table_name.as_ptr(), path.as_ptr()) };
        assert!(unsafe { result_is_ok(reg_result) });
        unsafe { result_free(reg_result) };

        // Query the registered table
        let sql = CString::new("SELECT id, name FROM test_table ORDER BY id").unwrap();
        let df_result = unsafe { session_sql(rt_ptr, sess_ptr, sql.as_ptr()) };
        assert!(unsafe { result_is_ok(df_result) });
        let df_ptr = unsafe { result_unwrap(df_result) } as *mut DFDataFrame;
        unsafe { result_free(df_result) };

        let rt = unsafe { &*rt_ptr };
        let df = unsafe { &*df_ptr };
        let batches = rt
            .runtime
            .block_on(df.dataframe.clone().collect())
            .expect("collect should succeed");
        assert!(!batches.is_empty());
        let total_rows: usize = batches.iter().map(|b| b.num_rows()).sum();
        assert_eq!(total_rows, 2);

        unsafe { crate::dataframe::dataframe_free(df_ptr) };
        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn session_register_csv_null_pointers_return_error() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let table_name = CString::new("t").unwrap();
        let path = CString::new("/tmp/x.csv").unwrap();

        // null runtime
        let r = unsafe {
            session_register_csv(
                std::ptr::null_mut(),
                sess_ptr,
                table_name.as_ptr(),
                path.as_ptr(),
            )
        };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        // null session
        let r = unsafe {
            session_register_csv(
                rt_ptr,
                std::ptr::null_mut(),
                table_name.as_ptr(),
                path.as_ptr(),
            )
        };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        // null table_name
        let r = unsafe { session_register_csv(rt_ptr, sess_ptr, std::ptr::null(), path.as_ptr()) };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        // null path
        let r = unsafe {
            session_register_csv(rt_ptr, sess_ptr, table_name.as_ptr(), std::ptr::null())
        };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }
}
