package net.carcdr.datafusionpanama;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/** Manages the lifecycle of a DataFusion session context. */
public interface DataFusionSession extends AutoCloseable {

    /**
     * Executes a SQL query and returns an opaque DataFrame.
     *
     * @param query the SQL query string
     * @return a DataFrame representing the query result
     * @throws DataFusionException if the SQL query is invalid or execution fails
     */
    DataFusionDataFrame sql(String query) throws DataFusionException;

    /**
     * Registers a CSV file as a named table in this session.
     *
     * @param tableName the name to register the table under
     * @param path the path to the CSV file
     * @throws DataFusionException if registration fails
     */
    void registerCsv(String tableName, Path path) throws DataFusionException;

    /**
     * Returns the native pointer for use by other FFI classes.
     *
     * @return the native memory segment holding the pointer
     * @throws IllegalStateException if the session has been closed
     */
    MemorySegment nativePointer();

    @Override
    void close();
}
