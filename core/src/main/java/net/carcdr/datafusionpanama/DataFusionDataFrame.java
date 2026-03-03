package net.carcdr.datafusionpanama;

import java.lang.foreign.MemorySegment;

/** Manages the lifecycle of a DataFusion DataFrame. */
public interface DataFusionDataFrame extends AutoCloseable {

    /**
     * Collects this DataFrame into Arrow record batches and returns a reader to iterate them.
     *
     * @return a reader over the collected record batches
     * @throws DataFusionException if collection fails
     */
    RecordBatchReader collect() throws DataFusionException;

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
