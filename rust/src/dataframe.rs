use std::ffi::c_void;
use std::ptr;
use std::sync::Arc;

use arrow::ffi_stream::FFI_ArrowArrayStream;
use arrow::record_batch::RecordBatchIterator;
use datafusion::dataframe::DataFrame;

use crate::result::{ffi_result, DFResult};
use crate::runtime::DFRuntime;

pub struct DFDataFrame {
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

/// Collects a DataFrame into Arrow batches and exports them as an `ArrowArrayStream`.
///
/// The caller must allocate a region of memory at least `sizeof(ArrowArrayStream)` bytes
/// at `stream_out`. This function populates that memory with a valid `ArrowArrayStream`
/// whose function pointers can be used to iterate over the result batches.
///
/// # Safety
/// - `runtime` must be a valid pointer returned by `runtime_new`.
/// - `dataframe` must be a valid pointer returned by `session_sql`.
/// - `stream_out` must point to a region of at least 40 bytes (the size of `ArrowArrayStream`).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_collect(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    stream_out: *mut c_void,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!stream_out.is_null(), "stream_out pointer must not be null");

        let rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let schema = Arc::new(df.dataframe.schema().as_arrow().clone());
        let df_clone = df.dataframe.clone();
        let batches = rt.runtime.block_on(df_clone.collect())?;

        let reader = RecordBatchIterator::new(batches.into_iter().map(Ok), schema);
        let ffi_stream = FFI_ArrowArrayStream::new(Box::new(reader));

        unsafe {
            ptr::write(stream_out as *mut FFI_ArrowArrayStream, ffi_stream);
        }

        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr::null_mut());
        result
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{result_free, result_is_ok};
    use crate::runtime::{runtime_free, runtime_new};
    use crate::session::{session_free, session_new, session_sql, DFSession};
    use arrow::ffi_stream::ArrowArrayStreamReader;
    use arrow::record_batch::RecordBatchReader;
    use std::ffi::CString;
    use std::mem::MaybeUninit;

    #[test]
    fn dataframe_free_with_null_is_safe() {
        unsafe { dataframe_free(std::ptr::null_mut()) };
    }

    #[test]
    fn dataframe_collect_produces_valid_stream() {
        // Setup: runtime → session → SQL → dataframe
        let rt_result = runtime_new();
        let rt_ptr = unsafe { crate::result::result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { crate::result::result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let sql = CString::new("SELECT 1 AS a, 2 AS b").unwrap();
        let df_result = unsafe { session_sql(rt_ptr, sess_ptr, sql.as_ptr()) };
        let df_ptr = unsafe { crate::result::result_unwrap(df_result) } as *mut DFDataFrame;
        unsafe { result_free(df_result) };

        // Collect into ArrowArrayStream
        let mut stream = MaybeUninit::<FFI_ArrowArrayStream>::uninit();
        let collect_result =
            unsafe { dataframe_collect(rt_ptr, df_ptr, stream.as_mut_ptr() as *mut c_void) };
        assert!(unsafe { result_is_ok(collect_result) });
        unsafe { result_free(collect_result) };

        let stream = unsafe { stream.assume_init() };

        // Read batches via ArrowArrayStreamReader
        let reader =
            ArrowArrayStreamReader::try_new(stream).expect("stream reader should be valid");
        let schema = reader.schema();
        assert_eq!(schema.fields().len(), 2);
        assert_eq!(schema.field(0).name(), "a");
        assert_eq!(schema.field(1).name(), "b");

        let batches: Vec<_> = reader.map(|r| r.expect("batch should be valid")).collect();
        assert!(!batches.is_empty());
        assert_eq!(batches[0].num_rows(), 1);

        // Cleanup
        unsafe { dataframe_free(df_ptr) };
        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn dataframe_collect_null_runtime_returns_error() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { crate::result::result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { crate::result::result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let sql = CString::new("SELECT 1").unwrap();
        let df_result = unsafe { session_sql(rt_ptr, sess_ptr, sql.as_ptr()) };
        let df_ptr = unsafe { crate::result::result_unwrap(df_result) } as *mut DFDataFrame;
        unsafe { result_free(df_result) };

        let mut stream = MaybeUninit::<FFI_ArrowArrayStream>::uninit();
        let collect_result = unsafe {
            dataframe_collect(
                std::ptr::null_mut(),
                df_ptr,
                stream.as_mut_ptr() as *mut c_void,
            )
        };
        assert!(!unsafe { result_is_ok(collect_result) });
        unsafe { result_free(collect_result) };

        unsafe { dataframe_free(df_ptr) };
        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn dataframe_collect_null_dataframe_returns_error() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { crate::result::result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let mut stream = MaybeUninit::<FFI_ArrowArrayStream>::uninit();
        let collect_result = unsafe {
            dataframe_collect(
                rt_ptr,
                std::ptr::null_mut(),
                stream.as_mut_ptr() as *mut c_void,
            )
        };
        assert!(!unsafe { result_is_ok(collect_result) });
        unsafe { result_free(collect_result) };

        unsafe { runtime_free(rt_ptr) };
    }

    #[test]
    fn dataframe_collect_null_stream_out_returns_error() {
        let rt_result = runtime_new();
        let rt_ptr = unsafe { crate::result::result_unwrap(rt_result) } as *mut DFRuntime;
        unsafe { result_free(rt_result) };

        let sess_result = unsafe { session_new(rt_ptr) };
        let sess_ptr = unsafe { crate::result::result_unwrap(sess_result) } as *mut DFSession;
        unsafe { result_free(sess_result) };

        let sql = CString::new("SELECT 1").unwrap();
        let df_result = unsafe { session_sql(rt_ptr, sess_ptr, sql.as_ptr()) };
        let df_ptr = unsafe { crate::result::result_unwrap(df_result) } as *mut DFDataFrame;
        unsafe { result_free(df_result) };

        let collect_result = unsafe { dataframe_collect(rt_ptr, df_ptr, std::ptr::null_mut()) };
        assert!(!unsafe { result_is_ok(collect_result) });
        unsafe { result_free(collect_result) };

        unsafe { dataframe_free(df_ptr) };
        unsafe { session_free(sess_ptr) };
        unsafe { runtime_free(rt_ptr) };
    }
}
