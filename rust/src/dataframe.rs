use datafusion::dataframe::DataFrame;

pub struct DFDataFrame {
    #[allow(dead_code)] // Used by future collect FFI
    pub(crate) dataframe: DataFrame,
}

/// Frees a `DFDataFrame` previously returned by `session_sql`.
///
/// # Safety
/// `dataframe` must be a valid pointer returned by `session_sql`, or null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_free(dataframe: *mut DFDataFrame) {
    if !dataframe.is_null() {
        drop(unsafe { Box::from_raw(dataframe) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dataframe_free_with_null_is_safe() {
        unsafe { dataframe_free(std::ptr::null_mut()) };
    }
}
