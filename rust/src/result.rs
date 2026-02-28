use std::ffi::{c_char, c_void, CString};
use std::ptr;

/// Opaque result type returned by FFI functions. Holds either a success value
/// (as a raw pointer) or an error message.
pub struct DFResult {
    value: *mut c_void,
    error: Option<CString>,
}

impl DFResult {
    pub fn ok(value: *mut c_void) -> Self {
        Self { value, error: None }
    }

    pub fn err(message: impl Into<String>) -> Self {
        Self {
            value: ptr::null_mut(),
            error: Some(CString::new(message.into()).expect("error message contained null byte")),
        }
    }

    pub fn is_ok(&self) -> bool {
        self.error.is_none()
    }

    pub fn unwrap_value(&self) -> *mut c_void {
        self.value
    }

    pub fn error_message_ptr(&self) -> *const c_char {
        match &self.error {
            Some(s) => s.as_ptr(),
            None => ptr::null(),
        }
    }
}

/// Wraps a block returning `Result<*mut c_void, Box<dyn std::error::Error>>` in
/// panic-catching logic, producing a `*mut DFResult`.
#[allow(unused_macros)]
macro_rules! ffi_result {
    ($body:expr) => {{
        let outcome = ::std::panic::catch_unwind(::std::panic::AssertUnwindSafe(|| $body));
        let df_result = match outcome {
            Ok(Ok(ptr)) => $crate::result::DFResult::ok(ptr),
            Ok(Err(e)) => $crate::result::DFResult::err(e.to_string()),
            Err(_) => $crate::result::DFResult::err("internal panic"),
        };
        Box::into_raw(Box::new(df_result))
    }};
}
#[allow(unused_imports)]
pub(crate) use ffi_result;

// ---------------------------------------------------------------------------
// FFI functions
// ---------------------------------------------------------------------------

/// # Safety
/// `result` must be a valid pointer returned by an FFI function that produces `DFResult`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn result_is_ok(result: *mut DFResult) -> bool {
    let result = unsafe { &*result };
    result.is_ok()
}

/// # Safety
/// `result` must be a valid pointer returned by an FFI function that produces `DFResult`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn result_unwrap(result: *mut DFResult) -> *mut c_void {
    let result = unsafe { &*result };
    result.unwrap_value()
}

/// # Safety
/// `result` must be a valid pointer returned by an FFI function that produces `DFResult`.
/// The returned string pointer is only valid while the `DFResult` is alive.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn result_error_message(result: *mut DFResult) -> *const c_char {
    let result = unsafe { &*result };
    result.error_message_ptr()
}

/// # Safety
/// `result` must be a valid pointer returned by an FFI function that produces `DFResult`,
/// or null. After this call the pointer is invalid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn result_free(result: *mut DFResult) {
    if !result.is_null() {
        drop(Box::from_raw(result));
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;

    // -- DFResult struct tests --

    #[test]
    fn ok_result_is_ok() {
        let r = DFResult::ok(ptr::null_mut());
        assert!(r.is_ok());
    }

    #[test]
    fn err_result_is_not_ok() {
        let r = DFResult::err("boom");
        assert!(!r.is_ok());
    }

    #[test]
    fn unwrap_returns_value() {
        let val: *mut c_void = 0xDEAD as *mut c_void;
        let r = DFResult::ok(val);
        assert_eq!(r.unwrap_value(), val);
    }

    #[test]
    fn error_message_returns_correct_string() {
        let r = DFResult::err("something failed");
        let ptr = r.error_message_ptr();
        assert!(!ptr.is_null());
        let msg = unsafe { CStr::from_ptr(ptr) }.to_str().unwrap();
        assert_eq!(msg, "something failed");
    }

    #[test]
    fn ok_result_with_null_value() {
        let r = DFResult::ok(ptr::null_mut());
        assert!(r.is_ok());
        assert!(r.unwrap_value().is_null());
        assert!(r.error_message_ptr().is_null());
    }

    // -- FFI function tests --

    #[test]
    fn ffi_result_is_ok_true() {
        let r = Box::into_raw(Box::new(DFResult::ok(ptr::null_mut())));
        assert!(unsafe { result_is_ok(r) });
        unsafe { drop(Box::from_raw(r)) };
    }

    #[test]
    fn ffi_result_is_ok_false() {
        let r = Box::into_raw(Box::new(DFResult::err("err")));
        assert!(unsafe { !result_is_ok(r) });
        unsafe { drop(Box::from_raw(r)) };
    }

    #[test]
    fn ffi_result_unwrap_extracts_value() {
        let val: *mut c_void = 0xBEEF as *mut c_void;
        let r = Box::into_raw(Box::new(DFResult::ok(val)));
        assert_eq!(unsafe { result_unwrap(r) }, val);
        unsafe { drop(Box::from_raw(r)) };
    }

    #[test]
    fn ffi_result_error_message_returns_c_string() {
        let r = Box::into_raw(Box::new(DFResult::err("oops")));
        let ptr = unsafe { result_error_message(r) };
        let msg = unsafe { CStr::from_ptr(ptr) }.to_str().unwrap();
        assert_eq!(msg, "oops");
        unsafe { drop(Box::from_raw(r)) };
    }

    #[test]
    fn ffi_result_free_ok() {
        let r = Box::into_raw(Box::new(DFResult::ok(ptr::null_mut())));
        unsafe { result_free(r) }; // should not crash
    }

    #[test]
    fn ffi_result_free_err() {
        let r = Box::into_raw(Box::new(DFResult::err("bye")));
        unsafe { result_free(r) }; // should not crash
    }

    // -- ffi_result! macro tests --

    #[test]
    fn macro_ok_path() {
        let r = ffi_result!({
            let v: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(0xCAFE as *mut c_void);
            v
        });
        assert!(unsafe { result_is_ok(r) });
        assert_eq!(unsafe { result_unwrap(r) }, 0xCAFE as *mut c_void);
        unsafe { result_free(r) };
    }

    #[test]
    fn macro_err_path() {
        let r = ffi_result!({
            let v: Result<*mut c_void, Box<dyn std::error::Error>> = Err("macro error".into());
            v
        });
        assert!(unsafe { !result_is_ok(r) });
        let msg = unsafe { CStr::from_ptr(result_error_message(r)) }
            .to_str()
            .unwrap();
        assert_eq!(msg, "macro error");
        unsafe { result_free(r) };
    }

    #[test]
    fn macro_panic_path() {
        let r = ffi_result!({
            panic!("kaboom");
            #[allow(unreachable_code)]
            {
                let v: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr::null_mut());
                v
            }
        });
        assert!(unsafe { !result_is_ok(r) });
        let msg = unsafe { CStr::from_ptr(result_error_message(r)) }
            .to_str()
            .unwrap();
        assert_eq!(msg, "internal panic");
        unsafe { result_free(r) };
    }
}
