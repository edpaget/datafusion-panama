use std::ffi::c_void;

use datafusion::execution::context::SessionContext;

use crate::result::{ffi_result, DFResult};
use crate::runtime::DFRuntime;

pub struct DFSession {
    #[allow(dead_code)] // Used by session_sql FFI in a later step
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{result_free, result_is_ok, result_unwrap};
    use crate::runtime::{runtime_free, runtime_new};

    #[test]
    fn session_create_and_free() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = session_new(rt_ptr);
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
    fn session_holds_valid_context() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = session_new(rt_ptr);
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
