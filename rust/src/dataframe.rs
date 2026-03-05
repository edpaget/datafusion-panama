use std::ffi::{c_char, c_int, c_void, CStr};
use std::ptr;
use std::sync::Arc;

use arrow::ffi_stream::FFI_ArrowArrayStream;
use arrow::record_batch::RecordBatchIterator;
use datafusion::common::JoinType;
use datafusion::dataframe::DataFrame;
use datafusion::logical_expr::SortExpr;
use datafusion::prelude::Expr;

use crate::result::{ffi_result, DFResult};
use crate::runtime::DFRuntime;

pub struct DFDataFrame {
    pub(crate) dataframe: DataFrame,
}

// ---------------------------------------------------------------------------
// Helpers (private)
// ---------------------------------------------------------------------------

/// Extract a `Vec<&str>` from a C string pointer array.
///
/// # Safety
/// `ptrs` must point to `len` valid, null-terminated C string pointers.
unsafe fn parse_c_str_array<'a>(
    ptrs: *const *const c_char,
    len: usize,
) -> Result<Vec<&'a str>, Box<dyn std::error::Error>> {
    let slice = unsafe { std::slice::from_raw_parts(ptrs, len) };
    let mut result = Vec::with_capacity(len);
    for &p in slice {
        assert!(!p.is_null(), "string array element must not be null");
        result.push(unsafe { CStr::from_ptr(p) }.to_str()?);
    }
    Ok(result)
}

/// Parse SQL expression strings against a DataFrame's schema.
fn parse_sql_exprs(
    df: &DataFrame,
    sql_strs: &[&str],
) -> Result<Vec<Expr>, Box<dyn std::error::Error>> {
    sql_strs.iter().map(|s| Ok(df.parse_sql_expr(s)?)).collect()
}

/// Parse a sort expression like `"col DESC NULLS LAST"`.
///
/// Format: `expr [ASC|DESC] [NULLS FIRST|NULLS LAST]`
/// Defaults: ASC, NULLS LAST for ASC / NULLS FIRST for DESC (SQL standard).
fn parse_sort_expr(df: &DataFrame, sql: &str) -> Result<SortExpr, Box<dyn std::error::Error>> {
    let trimmed = sql.trim();
    let upper = trimmed.to_uppercase();

    // Strip NULLS FIRST / NULLS LAST suffix
    let (rest, nulls_opt) = if upper.ends_with(" NULLS FIRST") {
        (&trimmed[..trimmed.len() - " NULLS FIRST".len()], Some(true))
    } else if upper.ends_with(" NULLS LAST") {
        (&trimmed[..trimmed.len() - " NULLS LAST".len()], Some(false))
    } else {
        (trimmed, None)
    };

    // Strip ASC / DESC suffix
    let rest_upper = rest.to_uppercase();
    let (expr_str, asc) = if rest_upper.ends_with(" ASC") {
        (&rest[..rest.len() - " ASC".len()], true)
    } else if rest_upper.ends_with(" DESC") {
        (&rest[..rest.len() - " DESC".len()], false)
    } else {
        (rest, true) // default ASC
    };

    // SQL standard: ASC → NULLS LAST, DESC → NULLS FIRST
    let nulls_first = nulls_opt.unwrap_or(!asc);

    let expr = df.parse_sql_expr(expr_str.trim())?;
    Ok(SortExpr::new(expr, asc, nulls_first))
}

/// Map an integer to DataFusion's `JoinType`.
fn join_type_from_int(value: c_int) -> Result<JoinType, Box<dyn std::error::Error>> {
    match value {
        0 => Ok(JoinType::Inner),
        1 => Ok(JoinType::Left),
        2 => Ok(JoinType::Right),
        3 => Ok(JoinType::Full),
        4 => Ok(JoinType::LeftSemi),
        5 => Ok(JoinType::RightSemi),
        6 => Ok(JoinType::LeftAnti),
        7 => Ok(JoinType::RightAnti),
        _ => Err(format!("invalid join type: {}", value).into()),
    }
}

// ---------------------------------------------------------------------------
// FFI: free
// ---------------------------------------------------------------------------

/// Frees a `DFDataFrame` previously returned by `session_sql` or a DataFrame operation.
///
/// # Safety
/// `dataframe` must be a valid pointer returned by an FFI function, or null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_free(dataframe: *mut DFDataFrame) {
    if !dataframe.is_null() {
        drop(unsafe { Box::from_raw(dataframe) });
    }
}

// ---------------------------------------------------------------------------
// FFI: collect
// ---------------------------------------------------------------------------

/// Collects a DataFrame into Arrow batches and exports them as an `ArrowArrayStream`.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `stream_out` must point to at least `sizeof(ArrowArrayStream)` bytes.
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

// ---------------------------------------------------------------------------
// FFI: Tier 1 — Core operations
// ---------------------------------------------------------------------------

/// Filters a DataFrame by a SQL expression.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `expr` must be a valid null-terminated C string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_filter(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    expr: *const c_char,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!expr.is_null(), "expr pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let expr_str = unsafe { CStr::from_ptr(expr) }.to_str()?;
        let parsed = df.dataframe.parse_sql_expr(expr_str)?;
        let result_df = df.dataframe.clone().filter(parsed)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Selects columns/expressions from a DataFrame using SQL expression strings.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `exprs` must point to `len` valid null-terminated C string pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_select(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    exprs: *const *const c_char,
    len: usize,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!exprs.is_null(), "exprs pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let sql_strs = unsafe { parse_c_str_array(exprs, len) }?;
        let parsed = parse_sql_exprs(&df.dataframe, &sql_strs)?;
        let result_df = df.dataframe.clone().select(parsed)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Limits a DataFrame to skip rows and fetch at most `fetch` rows.
/// A `fetch` value less than 0 means no fetch limit.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_limit(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    skip: c_int,
    fetch: c_int,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(skip >= 0, "skip must not be negative");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let fetch_opt = if fetch < 0 {
            None
        } else {
            Some(fetch as usize)
        };
        let result_df = df.dataframe.clone().limit(skip as usize, fetch_opt)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Sorts a DataFrame by the given sort expression strings.
///
/// Each expression can be `"col [ASC|DESC] [NULLS FIRST|NULLS LAST]"`.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `exprs` must point to `len` valid null-terminated C string pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_sort(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    exprs: *const *const c_char,
    len: usize,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!exprs.is_null(), "exprs pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let sql_strs = unsafe { parse_c_str_array(exprs, len) }?;
        let sort_exprs: Result<Vec<SortExpr>, Box<dyn std::error::Error>> = sql_strs
            .iter()
            .map(|s| parse_sort_expr(&df.dataframe, s))
            .collect();
        let result_df = df.dataframe.clone().sort(sort_exprs?)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns a DataFrame with distinct rows.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_distinct(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let result_df = df.dataframe.clone().distinct()?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns the number of rows in a DataFrame.
///
/// The count is returned as the pointer value in `DFResult` (cast from `usize`).
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_count(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");

        let rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let count = rt.runtime.block_on(df.dataframe.clone().count())?;

        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(count as *mut c_void);
        result
    })
}

// ---------------------------------------------------------------------------
// FFI: Tier 2 — Extended operations
// ---------------------------------------------------------------------------

/// Aggregates a DataFrame with group-by and aggregate expressions.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `group_exprs` points to `group_len` SQL strings.
/// - `aggr_exprs` points to `aggr_len` SQL strings.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_aggregate(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    group_exprs: *const *const c_char,
    group_len: usize,
    aggr_exprs: *const *const c_char,
    aggr_len: usize,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(
            !group_exprs.is_null(),
            "group_exprs pointer must not be null"
        );
        assert!(!aggr_exprs.is_null(), "aggr_exprs pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let group_strs = unsafe { parse_c_str_array(group_exprs, group_len) }?;
        let aggr_strs = unsafe { parse_c_str_array(aggr_exprs, aggr_len) }?;
        let groups = parse_sql_exprs(&df.dataframe, &group_strs)?;
        let aggrs = parse_sql_exprs(&df.dataframe, &aggr_strs)?;
        let result_df = df.dataframe.clone().aggregate(groups, aggrs)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Selects columns by name from a DataFrame.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `cols` points to `len` valid null-terminated C string pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_select_columns(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    cols: *const *const c_char,
    len: usize,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!cols.is_null(), "cols pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let col_strs = unsafe { parse_c_str_array(cols, len) }?;
        let result_df = df.dataframe.clone().select_columns(&col_strs)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Drops columns by name from a DataFrame.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `cols` points to `len` valid null-terminated C string pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_drop_columns(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    cols: *const *const c_char,
    len: usize,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!cols.is_null(), "cols pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let col_strs = unsafe { parse_c_str_array(cols, len) }?;
        let result_df = df.dataframe.clone().drop_columns(&col_strs)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Adds a new column to a DataFrame.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `name` and `expr` must be valid null-terminated C strings.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_with_column(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    name: *const c_char,
    expr: *const c_char,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!name.is_null(), "name pointer must not be null");
        assert!(!expr.is_null(), "expr pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let name_str = unsafe { CStr::from_ptr(name) }.to_str()?;
        let expr_str = unsafe { CStr::from_ptr(expr) }.to_str()?;
        let parsed = df.dataframe.parse_sql_expr(expr_str)?;
        let result_df = df.dataframe.clone().with_column(name_str, parsed)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Renames a column in a DataFrame.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
/// - `old_name` and `new_name` must be valid null-terminated C strings.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_with_column_renamed(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    old_name: *const c_char,
    new_name: *const c_char,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");
        assert!(!old_name.is_null(), "old_name pointer must not be null");
        assert!(!new_name.is_null(), "new_name pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let old_str = unsafe { CStr::from_ptr(old_name) }.to_str()?;
        let new_str = unsafe { CStr::from_ptr(new_name) }.to_str()?;
        let result_df = df.dataframe.clone().with_column_renamed(old_str, new_str)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns the execution plan of a DataFrame as a new DataFrame.
///
/// # Safety
/// - `runtime`, `dataframe` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_explain(
    runtime: *mut DFRuntime,
    dataframe: *mut DFDataFrame,
    verbose: bool,
    analyze: bool,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!dataframe.is_null(), "dataframe pointer must not be null");

        let _rt = unsafe { &*runtime };
        let df = unsafe { &*dataframe };

        let result_df = df.dataframe.clone().explain(verbose, analyze)?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

// ---------------------------------------------------------------------------
// FFI: Tier 3 — Two-DataFrame operations
// ---------------------------------------------------------------------------

/// Joins two DataFrames on the specified columns.
///
/// # Safety
/// - `runtime`, `left`, `right` must be valid pointers.
/// - `left_cols` points to `left_len` column name strings.
/// - `right_cols` points to `right_len` column name strings.
/// - `join_type` must be 0–7 (see `JoinType` mapping).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_join(
    runtime: *mut DFRuntime,
    left: *mut DFDataFrame,
    right: *mut DFDataFrame,
    join_type: c_int,
    left_cols: *const *const c_char,
    left_len: usize,
    right_cols: *const *const c_char,
    right_len: usize,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!left.is_null(), "left pointer must not be null");
        assert!(!right.is_null(), "right pointer must not be null");
        assert!(!left_cols.is_null(), "left_cols pointer must not be null");
        assert!(!right_cols.is_null(), "right_cols pointer must not be null");

        let _rt = unsafe { &*runtime };
        let left_df = unsafe { &*left };
        let right_df = unsafe { &*right };

        let jt = join_type_from_int(join_type)?;
        let l_cols = unsafe { parse_c_str_array(left_cols, left_len) }?;
        let r_cols = unsafe { parse_c_str_array(right_cols, right_len) }?;
        let result_df = left_df.dataframe.clone().join(
            right_df.dataframe.clone(),
            jt,
            &l_cols,
            &r_cols,
            None,
        )?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns the union of two DataFrames.
///
/// # Safety
/// - `runtime`, `left`, `right` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_union(
    runtime: *mut DFRuntime,
    left: *mut DFDataFrame,
    right: *mut DFDataFrame,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!left.is_null(), "left pointer must not be null");
        assert!(!right.is_null(), "right pointer must not be null");

        let _rt = unsafe { &*runtime };
        let left_df = unsafe { &*left };
        let right_df = unsafe { &*right };

        let result_df = left_df
            .dataframe
            .clone()
            .union(right_df.dataframe.clone())?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns the distinct union of two DataFrames.
///
/// # Safety
/// - `runtime`, `left`, `right` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_union_distinct(
    runtime: *mut DFRuntime,
    left: *mut DFDataFrame,
    right: *mut DFDataFrame,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!left.is_null(), "left pointer must not be null");
        assert!(!right.is_null(), "right pointer must not be null");

        let _rt = unsafe { &*runtime };
        let left_df = unsafe { &*left };
        let right_df = unsafe { &*right };

        let result_df = left_df
            .dataframe
            .clone()
            .union_distinct(right_df.dataframe.clone())?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns rows in `left` but not in `right`.
///
/// # Safety
/// - `runtime`, `left`, `right` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_except(
    runtime: *mut DFRuntime,
    left: *mut DFDataFrame,
    right: *mut DFDataFrame,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!left.is_null(), "left pointer must not be null");
        assert!(!right.is_null(), "right pointer must not be null");

        let _rt = unsafe { &*runtime };
        let left_df = unsafe { &*left };
        let right_df = unsafe { &*right };

        let result_df = left_df
            .dataframe
            .clone()
            .except(right_df.dataframe.clone())?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

/// Returns rows present in both DataFrames.
///
/// # Safety
/// - `runtime`, `left`, `right` must be valid pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dataframe_intersect(
    runtime: *mut DFRuntime,
    left: *mut DFDataFrame,
    right: *mut DFDataFrame,
) -> *mut DFResult {
    ffi_result!({
        assert!(!runtime.is_null(), "runtime pointer must not be null");
        assert!(!left.is_null(), "left pointer must not be null");
        assert!(!right.is_null(), "right pointer must not be null");

        let _rt = unsafe { &*runtime };
        let left_df = unsafe { &*left };
        let right_df = unsafe { &*right };

        let result_df = left_df
            .dataframe
            .clone()
            .intersect(right_df.dataframe.clone())?;

        let ptr = Box::into_raw(Box::new(DFDataFrame {
            dataframe: result_df,
        })) as *mut c_void;
        let result: Result<*mut c_void, Box<dyn std::error::Error>> = Ok(ptr);
        result
    })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{result_free, result_is_ok, result_unwrap};
    use crate::runtime::{runtime_free, runtime_new};
    use crate::session::{session_free, session_new, session_sql, DFSession};
    use arrow::ffi_stream::ArrowArrayStreamReader;
    use std::ffi::CString;
    use std::mem::MaybeUninit;

    // -- Test helpers --

    struct TestContext {
        rt_ptr: *mut DFRuntime,
        sess_ptr: *mut DFSession,
    }

    impl TestContext {
        fn new() -> Self {
            let rt_result = runtime_new();
            let rt_ptr = unsafe { result_unwrap(rt_result) } as *mut DFRuntime;
            unsafe { result_free(rt_result) };

            let sess_result = unsafe { session_new(rt_ptr) };
            let sess_ptr = unsafe { result_unwrap(sess_result) } as *mut DFSession;
            unsafe { result_free(sess_result) };

            TestContext { rt_ptr, sess_ptr }
        }

        fn sql(&self, query: &str) -> *mut DFDataFrame {
            let sql = CString::new(query).unwrap();
            let df_result = unsafe { session_sql(self.rt_ptr, self.sess_ptr, sql.as_ptr()) };
            assert!(unsafe { result_is_ok(df_result) });
            let df_ptr = unsafe { result_unwrap(df_result) } as *mut DFDataFrame;
            unsafe { result_free(df_result) };
            df_ptr
        }
    }

    impl Drop for TestContext {
        fn drop(&mut self) {
            unsafe { session_free(self.sess_ptr) };
            unsafe { runtime_free(self.rt_ptr) };
        }
    }

    fn unwrap_df_result(result: *mut DFResult) -> *mut DFDataFrame {
        if !unsafe { result_is_ok(result) } {
            let msg_ptr = unsafe { crate::result::result_error_message(result) };
            let msg = unsafe { std::ffi::CStr::from_ptr(msg_ptr) }
                .to_str()
                .unwrap_or("unknown error");
            unsafe { result_free(result) };
            panic!("expected OK result, got error: {}", msg);
        }
        let ptr = unsafe { result_unwrap(result) } as *mut DFDataFrame;
        unsafe { result_free(result) };
        ptr
    }

    fn collect_batches(
        ctx: &TestContext,
        df_ptr: *mut DFDataFrame,
    ) -> Vec<arrow::record_batch::RecordBatch> {
        let mut stream = MaybeUninit::<FFI_ArrowArrayStream>::uninit();
        let result =
            unsafe { dataframe_collect(ctx.rt_ptr, df_ptr, stream.as_mut_ptr() as *mut c_void) };
        assert!(unsafe { result_is_ok(result) });
        unsafe { result_free(result) };
        let stream = unsafe { stream.assume_init() };
        let reader = ArrowArrayStreamReader::try_new(stream).unwrap();
        reader.map(|r| r.unwrap()).collect()
    }

    fn total_rows(batches: &[arrow::record_batch::RecordBatch]) -> usize {
        batches.iter().map(|b| b.num_rows()).sum()
    }

    // -- Free --

    #[test]
    fn dataframe_free_with_null_is_safe() {
        unsafe { dataframe_free(std::ptr::null_mut()) };
    }

    // -- Collect --

    #[test]
    fn dataframe_collect_produces_valid_stream() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a, 2 AS b");

        let batches = collect_batches(&ctx, df_ptr);
        assert_eq!(total_rows(&batches), 1);
        assert_eq!(batches[0].schema().fields().len(), 2);
        assert_eq!(batches[0].schema().field(0).name(), "a");
        assert_eq!(batches[0].schema().field(1).name(), "b");

        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn dataframe_collect_null_runtime_returns_error() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1");

        let mut stream = MaybeUninit::<FFI_ArrowArrayStream>::uninit();
        let result = unsafe {
            dataframe_collect(
                std::ptr::null_mut(),
                df_ptr,
                stream.as_mut_ptr() as *mut c_void,
            )
        };
        assert!(!unsafe { result_is_ok(result) });
        unsafe { result_free(result) };
        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn dataframe_collect_null_dataframe_returns_error() {
        let ctx = TestContext::new();
        let mut stream = MaybeUninit::<FFI_ArrowArrayStream>::uninit();
        let result = unsafe {
            dataframe_collect(
                ctx.rt_ptr,
                std::ptr::null_mut(),
                stream.as_mut_ptr() as *mut c_void,
            )
        };
        assert!(!unsafe { result_is_ok(result) });
        unsafe { result_free(result) };
    }

    #[test]
    fn dataframe_collect_null_stream_out_returns_error() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1");
        let result = unsafe { dataframe_collect(ctx.rt_ptr, df_ptr, std::ptr::null_mut()) };
        assert!(!unsafe { result_is_ok(result) });
        unsafe { result_free(result) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- parse_sort_expr --

    #[test]
    fn parse_sort_expr_default_asc() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");
        let df = unsafe { &*df_ptr };
        let sort = parse_sort_expr(&df.dataframe, "a").unwrap();
        assert!(sort.asc);
        assert!(!sort.nulls_first); // SQL default for ASC: NULLS LAST
        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn parse_sort_expr_desc() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");
        let df = unsafe { &*df_ptr };
        let sort = parse_sort_expr(&df.dataframe, "a DESC").unwrap();
        assert!(!sort.asc);
        assert!(sort.nulls_first); // SQL default for DESC: NULLS FIRST
        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn parse_sort_expr_asc_nulls_first() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");
        let df = unsafe { &*df_ptr };
        let sort = parse_sort_expr(&df.dataframe, "a ASC NULLS FIRST").unwrap();
        assert!(sort.asc);
        assert!(sort.nulls_first);
        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn parse_sort_expr_desc_nulls_last() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");
        let df = unsafe { &*df_ptr };
        let sort = parse_sort_expr(&df.dataframe, "a DESC NULLS LAST").unwrap();
        assert!(!sort.asc);
        assert!(!sort.nulls_first);
        unsafe { dataframe_free(df_ptr) };
    }

    // -- join_type_from_int --

    #[test]
    fn join_type_from_int_valid() {
        assert_eq!(join_type_from_int(0).unwrap(), JoinType::Inner);
        assert_eq!(join_type_from_int(1).unwrap(), JoinType::Left);
        assert_eq!(join_type_from_int(2).unwrap(), JoinType::Right);
        assert_eq!(join_type_from_int(3).unwrap(), JoinType::Full);
        assert_eq!(join_type_from_int(4).unwrap(), JoinType::LeftSemi);
        assert_eq!(join_type_from_int(5).unwrap(), JoinType::RightSemi);
        assert_eq!(join_type_from_int(6).unwrap(), JoinType::LeftAnti);
        assert_eq!(join_type_from_int(7).unwrap(), JoinType::RightAnti);
    }

    #[test]
    fn join_type_from_int_invalid() {
        assert!(join_type_from_int(8).is_err());
        assert!(join_type_from_int(-1).is_err());
    }

    // -- filter --

    #[test]
    fn dataframe_filter_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(id, name)");
        let expr = CString::new("id > 1").unwrap();
        let result = unsafe { dataframe_filter(ctx.rt_ptr, df_ptr, expr.as_ptr()) };
        let filtered = unwrap_df_result(result);

        let batches = collect_batches(&ctx, filtered);
        assert_eq!(total_rows(&batches), 2);

        unsafe { dataframe_free(filtered) };
        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn dataframe_filter_null_pointers_return_error() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");
        let expr = CString::new("a > 0").unwrap();

        // null runtime
        let r = unsafe { dataframe_filter(std::ptr::null_mut(), df_ptr, expr.as_ptr()) };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        // null dataframe
        let r = unsafe { dataframe_filter(ctx.rt_ptr, std::ptr::null_mut(), expr.as_ptr()) };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        // null expr
        let r = unsafe { dataframe_filter(ctx.rt_ptr, df_ptr, std::ptr::null()) };
        assert!(!unsafe { result_is_ok(r) });
        unsafe { result_free(r) };

        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn dataframe_filter_invalid_expr_returns_error() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");
        let expr = CString::new("nonexistent_column > 0").unwrap();
        let result = unsafe { dataframe_filter(ctx.rt_ptr, df_ptr, expr.as_ptr()) };
        assert!(!unsafe { result_is_ok(result) });
        unsafe { result_free(result) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- select --

    #[test]
    fn dataframe_select_happy_path() {
        let ctx = TestContext::new();
        let df_ptr =
            ctx.sql("SELECT * FROM (VALUES (1, 'a', 10), (2, 'b', 20)) AS t(id, name, value)");

        let expr1 = CString::new("id").unwrap();
        let expr2 = CString::new("value * 2").unwrap();
        let exprs = [expr1.as_ptr(), expr2.as_ptr()];
        let result = unsafe { dataframe_select(ctx.rt_ptr, df_ptr, exprs.as_ptr(), exprs.len()) };
        let selected = unwrap_df_result(result);

        let batches = collect_batches(&ctx, selected);
        assert_eq!(total_rows(&batches), 2);
        assert_eq!(batches[0].schema().fields().len(), 2);

        unsafe { dataframe_free(selected) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- limit --

    #[test]
    fn dataframe_limit_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2), (3), (4), (5)) AS t(id)");
        let result = unsafe { dataframe_limit(ctx.rt_ptr, df_ptr, 1, 2) };
        let limited = unwrap_df_result(result);

        let batches = collect_batches(&ctx, limited);
        assert_eq!(total_rows(&batches), 2);

        unsafe { dataframe_free(limited) };
        unsafe { dataframe_free(df_ptr) };
    }

    #[test]
    fn dataframe_limit_no_fetch() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
        // skip 1, no fetch limit
        let result = unsafe { dataframe_limit(ctx.rt_ptr, df_ptr, 1, -1) };
        let limited = unwrap_df_result(result);

        let batches = collect_batches(&ctx, limited);
        assert_eq!(total_rows(&batches), 2);

        unsafe { dataframe_free(limited) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- sort --

    #[test]
    fn dataframe_sort_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (3, 'c'), (1, 'a'), (2, 'b')) AS t(id, name)");

        let expr = CString::new("id DESC").unwrap();
        let exprs = [expr.as_ptr()];
        let result = unsafe { dataframe_sort(ctx.rt_ptr, df_ptr, exprs.as_ptr(), exprs.len()) };
        let sorted = unwrap_df_result(result);

        let batches = collect_batches(&ctx, sorted);
        assert_eq!(total_rows(&batches), 3);
        // Verify descending order
        let id_col = batches[0]
            .column(0)
            .as_any()
            .downcast_ref::<arrow::array::Int64Array>()
            .unwrap();
        assert_eq!(id_col.value(0), 3);
        assert_eq!(id_col.value(1), 2);
        assert_eq!(id_col.value(2), 1);

        unsafe { dataframe_free(sorted) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- distinct --

    #[test]
    fn dataframe_distinct_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2), (1), (3), (2)) AS t(id)");
        let result = unsafe { dataframe_distinct(ctx.rt_ptr, df_ptr) };
        let distinct = unwrap_df_result(result);

        let batches = collect_batches(&ctx, distinct);
        assert_eq!(total_rows(&batches), 3);

        unsafe { dataframe_free(distinct) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- count --

    #[test]
    fn dataframe_count_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
        let result = unsafe { dataframe_count(ctx.rt_ptr, df_ptr) };
        assert!(unsafe { result_is_ok(result) });
        let count = unsafe { result_unwrap(result) } as usize;
        unsafe { result_free(result) };
        assert_eq!(count, 3);

        unsafe { dataframe_free(df_ptr) };
    }

    // -- aggregate --

    #[test]
    fn dataframe_aggregate_happy_path() {
        let ctx = TestContext::new();
        let df_ptr =
            ctx.sql("SELECT * FROM (VALUES ('a', 10), ('b', 20), ('a', 30)) AS t(grp, val)");

        let group = CString::new("grp").unwrap();
        let groups = [group.as_ptr()];
        let aggr = CString::new("SUM(val)").unwrap();
        let aggrs = [aggr.as_ptr()];

        let result = unsafe {
            dataframe_aggregate(
                ctx.rt_ptr,
                df_ptr,
                groups.as_ptr(),
                groups.len(),
                aggrs.as_ptr(),
                aggrs.len(),
            )
        };
        let aggregated = unwrap_df_result(result);

        let batches = collect_batches(&ctx, aggregated);
        assert_eq!(total_rows(&batches), 2); // two groups: 'a' and 'b'

        unsafe { dataframe_free(aggregated) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- select_columns --

    #[test]
    fn dataframe_select_columns_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1, 'a', 10)) AS t(id, name, value)");

        let col1 = CString::new("id").unwrap();
        let col2 = CString::new("name").unwrap();
        let cols = [col1.as_ptr(), col2.as_ptr()];
        let result =
            unsafe { dataframe_select_columns(ctx.rt_ptr, df_ptr, cols.as_ptr(), cols.len()) };
        let selected = unwrap_df_result(result);

        let batches = collect_batches(&ctx, selected);
        assert_eq!(batches[0].schema().fields().len(), 2);
        assert_eq!(batches[0].schema().field(0).name(), "id");
        assert_eq!(batches[0].schema().field(1).name(), "name");

        unsafe { dataframe_free(selected) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- drop_columns --

    #[test]
    fn dataframe_drop_columns_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1, 'a', 10)) AS t(id, name, value)");

        let col = CString::new("name").unwrap();
        let cols = [col.as_ptr()];
        let result =
            unsafe { dataframe_drop_columns(ctx.rt_ptr, df_ptr, cols.as_ptr(), cols.len()) };
        let dropped = unwrap_df_result(result);

        let batches = collect_batches(&ctx, dropped);
        assert_eq!(batches[0].schema().fields().len(), 2);
        assert_eq!(batches[0].schema().field(0).name(), "id");
        assert_eq!(batches[0].schema().field(1).name(), "value");

        unsafe { dataframe_free(dropped) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- with_column --

    #[test]
    fn dataframe_with_column_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT * FROM (VALUES (1, 10), (2, 20)) AS t(id, value)");

        let name = CString::new("doubled").unwrap();
        let expr = CString::new("value * 2").unwrap();
        let result =
            unsafe { dataframe_with_column(ctx.rt_ptr, df_ptr, name.as_ptr(), expr.as_ptr()) };
        let with_col = unwrap_df_result(result);

        let batches = collect_batches(&ctx, with_col);
        assert_eq!(batches[0].schema().fields().len(), 3);
        assert_eq!(batches[0].schema().field(2).name(), "doubled");

        unsafe { dataframe_free(with_col) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- with_column_renamed --

    #[test]
    fn dataframe_with_column_renamed_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS old_name");

        let old = CString::new("old_name").unwrap();
        let new = CString::new("new_name").unwrap();
        let result = unsafe {
            dataframe_with_column_renamed(ctx.rt_ptr, df_ptr, old.as_ptr(), new.as_ptr())
        };
        let renamed = unwrap_df_result(result);

        let batches = collect_batches(&ctx, renamed);
        assert_eq!(batches[0].schema().field(0).name(), "new_name");

        unsafe { dataframe_free(renamed) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- explain --

    #[test]
    fn dataframe_explain_happy_path() {
        let ctx = TestContext::new();
        let df_ptr = ctx.sql("SELECT 1 AS a");

        let result = unsafe { dataframe_explain(ctx.rt_ptr, df_ptr, false, false) };
        let explained = unwrap_df_result(result);

        let batches = collect_batches(&ctx, explained);
        assert!(!batches.is_empty());
        assert!(total_rows(&batches) > 0);

        unsafe { dataframe_free(explained) };
        unsafe { dataframe_free(df_ptr) };
    }

    // -- join --

    #[test]
    fn dataframe_join_happy_path() {
        let ctx = TestContext::new();
        let left_ptr =
            ctx.sql("SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS l(id, name)");
        let right_ptr = ctx.sql("SELECT * FROM (VALUES (1, 100), (2, 200)) AS r(id, score)");

        let lcol = CString::new("id").unwrap();
        let rcol = CString::new("id").unwrap();
        let lcols = [lcol.as_ptr()];
        let rcols = [rcol.as_ptr()];

        let result = unsafe {
            dataframe_join(
                ctx.rt_ptr,
                left_ptr,
                right_ptr,
                0, // INNER
                lcols.as_ptr(),
                lcols.len(),
                rcols.as_ptr(),
                rcols.len(),
            )
        };
        let joined = unwrap_df_result(result);

        let batches = collect_batches(&ctx, joined);
        assert_eq!(total_rows(&batches), 2); // only ids 1 and 2 match

        unsafe { dataframe_free(joined) };
        unsafe { dataframe_free(left_ptr) };
        unsafe { dataframe_free(right_ptr) };
    }

    // -- union --

    #[test]
    fn dataframe_union_happy_path() {
        let ctx = TestContext::new();
        let left_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2)) AS t(id)");
        let right_ptr = ctx.sql("SELECT * FROM (VALUES (3), (4)) AS t(id)");

        let result = unsafe { dataframe_union(ctx.rt_ptr, left_ptr, right_ptr) };
        let unioned = unwrap_df_result(result);

        let batches = collect_batches(&ctx, unioned);
        assert_eq!(total_rows(&batches), 4);

        unsafe { dataframe_free(unioned) };
        unsafe { dataframe_free(left_ptr) };
        unsafe { dataframe_free(right_ptr) };
    }

    // -- union_distinct --

    #[test]
    fn dataframe_union_distinct_happy_path() {
        let ctx = TestContext::new();
        let left_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2)) AS t(id)");
        let right_ptr = ctx.sql("SELECT * FROM (VALUES (2), (3)) AS t(id)");

        let result = unsafe { dataframe_union_distinct(ctx.rt_ptr, left_ptr, right_ptr) };
        let unioned = unwrap_df_result(result);

        let batches = collect_batches(&ctx, unioned);
        assert_eq!(total_rows(&batches), 3); // 1, 2, 3

        unsafe { dataframe_free(unioned) };
        unsafe { dataframe_free(left_ptr) };
        unsafe { dataframe_free(right_ptr) };
    }

    // -- except --

    #[test]
    fn dataframe_except_happy_path() {
        let ctx = TestContext::new();
        let left_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
        let right_ptr = ctx.sql("SELECT * FROM (VALUES (2)) AS t(id)");

        let result = unsafe { dataframe_except(ctx.rt_ptr, left_ptr, right_ptr) };
        let excepted = unwrap_df_result(result);

        let batches = collect_batches(&ctx, excepted);
        assert_eq!(total_rows(&batches), 2); // 1, 3

        unsafe { dataframe_free(excepted) };
        unsafe { dataframe_free(left_ptr) };
        unsafe { dataframe_free(right_ptr) };
    }

    // -- intersect --

    #[test]
    fn dataframe_intersect_happy_path() {
        let ctx = TestContext::new();
        let left_ptr = ctx.sql("SELECT * FROM (VALUES (1), (2), (3)) AS t(id)");
        let right_ptr = ctx.sql("SELECT * FROM (VALUES (2), (3), (4)) AS t(id)");

        let result = unsafe { dataframe_intersect(ctx.rt_ptr, left_ptr, right_ptr) };
        let intersected = unwrap_df_result(result);

        let batches = collect_batches(&ctx, intersected);
        assert_eq!(total_rows(&batches), 2); // 2, 3

        unsafe { dataframe_free(intersected) };
        unsafe { dataframe_free(left_ptr) };
        unsafe { dataframe_free(right_ptr) };
    }
}
