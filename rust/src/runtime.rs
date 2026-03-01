use std::ffi::c_void;

use crate::result::{ffi_result, DFResult};

pub struct DFRuntime {
    #[allow(dead_code)] // Accessed via raw pointer in session_new; Clippy can't see unsafe deref
    pub(crate) runtime: tokio::runtime::Runtime,
}

/// Creates a new Tokio multi-threaded runtime wrapped in a `DFResult`.
///
/// # Returns
/// A `*mut DFResult` whose success value is a `*mut DFRuntime`.
#[unsafe(no_mangle)]
pub extern "C" fn runtime_new() -> *mut DFResult {
    ffi_result!({
        let runtime = tokio::runtime::Runtime::new()?;
        let df_runtime = DFRuntime { runtime };
        let ptr = Box::into_raw(Box::new(df_runtime)) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Frees a `DFRuntime` previously returned by `runtime_new`.
///
/// # Safety
/// `runtime` must be a valid pointer returned by `runtime_new`, or null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn runtime_free(runtime: *mut DFRuntime) {
    if !runtime.is_null() {
        drop(unsafe { Box::from_raw(runtime) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{result_free, result_is_ok, result_unwrap};

    #[test]
    fn runtime_new_returns_ok_with_non_null_pointer() {
        let r = runtime_new();
        assert!(unsafe { result_is_ok(r) });
        let ptr = unsafe { result_unwrap(r) } as *mut DFRuntime;
        assert!(!ptr.is_null());
        unsafe { result_free(r) };
        unsafe { runtime_free(ptr) };
    }

    #[test]
    fn runtime_free_with_null_is_safe() {
        unsafe { runtime_free(std::ptr::null_mut()) };
    }

    #[test]
    fn runtime_free_with_valid_pointer() {
        let r = runtime_new();
        let ptr = unsafe { result_unwrap(r) } as *mut DFRuntime;
        unsafe { result_free(r) };
        unsafe { runtime_free(ptr) };
    }

    #[test]
    fn runtime_can_block_on_async_work() {
        let r = runtime_new();
        let ptr = unsafe { result_unwrap(r) } as *mut DFRuntime;
        unsafe { result_free(r) };

        let rt = unsafe { &*ptr };
        let value = rt.runtime.block_on(async { 42 });
        assert_eq!(value, 42);

        unsafe { runtime_free(ptr) };
    }
}
