use std::ffi::c_int;

mod result;
pub use result::*;

mod runtime;
pub use runtime::*;

mod session;
pub use session::*;

mod dataframe;
pub use dataframe::*;

/// Example function exported via C FFI for use with Java's Foreign Function & Memory API.
#[unsafe(no_mangle)]
pub extern "C" fn add(a: c_int, b: c_int) -> c_int {
    a + b
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_add() {
        assert_eq!(add(2, 3), 5);
    }
}
