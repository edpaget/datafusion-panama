package net.carcdr.datafusionpanama;

import java.lang.foreign.MemorySegment;

/**
 * Manages the lifecycle of a DataFusion DataFrame.
 *
 * <p>Each DataFrame holds a native pointer to a Rust-allocated object. When chaining operations
 * (e.g. {@code df.filter(...).sort(...).limit(...)}), intermediate DataFrames that are not
 * explicitly closed will be cleaned up automatically by the garbage collector. However, explicit
 * {@link #close()} or try-with-resources is recommended for deterministic resource release.
 */
public interface DataFusionDataFrame extends AutoCloseable {

    /**
     * Collects this DataFrame into Arrow record batches and returns a reader to iterate them.
     *
     * @return a reader over the collected record batches
     * @throws DataFusionException if collection fails
     */
    RecordBatchReader collect() throws DataFusionException;

    /**
     * Filters this DataFrame by a SQL expression.
     *
     * @param expr a SQL expression that evaluates to a boolean
     * @return a new DataFrame containing only rows where the expression is true
     * @throws DataFusionException if the expression is invalid or filtering fails
     */
    DataFusionDataFrame filter(String expr) throws DataFusionException;

    /**
     * Selects columns or expressions from this DataFrame using SQL expression strings.
     *
     * @param exprs one or more SQL expressions
     * @return a new DataFrame with the selected columns
     * @throws DataFusionException if any expression is invalid
     */
    DataFusionDataFrame select(String... exprs) throws DataFusionException;

    /**
     * Limits this DataFrame to at most {@code fetch} rows after skipping {@code skip} rows.
     *
     * @param skip number of rows to skip
     * @param fetch maximum number of rows to return, or -1 for no limit
     * @return a new DataFrame with the limit applied
     * @throws DataFusionException if the limit operation fails
     */
    DataFusionDataFrame limit(int skip, int fetch) throws DataFusionException;

    /**
     * Sorts this DataFrame by the given sort expression strings.
     *
     * <p>Each expression can include optional sort direction and null ordering, for example {@code
     * "col DESC NULLS LAST"}.
     *
     * @param exprs one or more sort expressions
     * @return a new sorted DataFrame
     * @throws DataFusionException if any sort expression is invalid
     */
    DataFusionDataFrame sort(String... exprs) throws DataFusionException;

    /**
     * Returns a DataFrame with distinct rows.
     *
     * @return a new DataFrame containing only unique rows
     * @throws DataFusionException if the distinct operation fails
     */
    DataFusionDataFrame distinct() throws DataFusionException;

    /**
     * Returns the number of rows in this DataFrame.
     *
     * @return the row count
     * @throws DataFusionException if counting fails
     */
    long count() throws DataFusionException;

    /**
     * Aggregates this DataFrame with group-by and aggregate expressions.
     *
     * @param groupExprs SQL expressions for the group-by columns
     * @param aggrExprs SQL expressions for the aggregate functions
     * @return a new aggregated DataFrame
     * @throws DataFusionException if any expression is invalid
     */
    DataFusionDataFrame aggregate(String[] groupExprs, String[] aggrExprs)
            throws DataFusionException;

    /**
     * Selects columns by name from this DataFrame.
     *
     * @param columns column names to select
     * @return a new DataFrame with only the named columns
     * @throws DataFusionException if any column name is invalid
     */
    DataFusionDataFrame selectColumns(String... columns) throws DataFusionException;

    /**
     * Drops columns by name from this DataFrame.
     *
     * @param columns column names to drop
     * @return a new DataFrame without the named columns
     * @throws DataFusionException if the drop operation fails
     */
    DataFusionDataFrame dropColumns(String... columns) throws DataFusionException;

    /**
     * Adds a new column computed from a SQL expression.
     *
     * @param name the name for the new column
     * @param expr a SQL expression for the column value
     * @return a new DataFrame with the additional column
     * @throws DataFusionException if the expression is invalid
     */
    DataFusionDataFrame withColumn(String name, String expr) throws DataFusionException;

    /**
     * Renames a column in this DataFrame.
     *
     * @param oldName the current column name
     * @param newName the desired column name
     * @return a new DataFrame with the column renamed
     * @throws DataFusionException if the rename operation fails
     */
    DataFusionDataFrame withColumnRenamed(String oldName, String newName)
            throws DataFusionException;

    /**
     * Returns the execution plan of this DataFrame as a new DataFrame.
     *
     * @param verbose whether to include detailed plan information
     * @param analyze whether to run the plan and include execution statistics
     * @return a new DataFrame containing the plan output
     * @throws DataFusionException if the explain operation fails
     */
    DataFusionDataFrame explain(boolean verbose, boolean analyze) throws DataFusionException;

    /**
     * Joins this DataFrame with another on the specified columns.
     *
     * @param right the right-hand DataFrame
     * @param joinType the type of join to perform
     * @param leftColumns join key column names from this DataFrame
     * @param rightColumns join key column names from the right DataFrame
     * @return a new joined DataFrame
     * @throws DataFusionException if the join operation fails
     */
    DataFusionDataFrame join(
            DataFusionDataFrame right,
            JoinType joinType,
            String[] leftColumns,
            String[] rightColumns)
            throws DataFusionException;

    /**
     * Returns the union of this DataFrame with another (including duplicates).
     *
     * @param other the other DataFrame
     * @return a new DataFrame containing rows from both
     * @throws DataFusionException if the union operation fails
     */
    DataFusionDataFrame union(DataFusionDataFrame other) throws DataFusionException;

    /**
     * Returns the distinct union of this DataFrame with another.
     *
     * @param other the other DataFrame
     * @return a new DataFrame containing distinct rows from both
     * @throws DataFusionException if the operation fails
     */
    DataFusionDataFrame unionDistinct(DataFusionDataFrame other) throws DataFusionException;

    /**
     * Returns rows in this DataFrame that are not in the other.
     *
     * @param other the other DataFrame
     * @return a new DataFrame with the set difference
     * @throws DataFusionException if the operation fails
     */
    DataFusionDataFrame except(DataFusionDataFrame other) throws DataFusionException;

    /**
     * Returns rows present in both this DataFrame and the other.
     *
     * @param other the other DataFrame
     * @return a new DataFrame with the set intersection
     * @throws DataFusionException if the operation fails
     */
    DataFusionDataFrame intersect(DataFusionDataFrame other) throws DataFusionException;

    /**
     * Returns the native pointer for use by other FFI classes.
     *
     * @return the native memory segment holding the pointer
     * @throws IllegalStateException if the DataFrame has been closed
     */
    MemorySegment nativePointer();

    @Override
    void close();
}
