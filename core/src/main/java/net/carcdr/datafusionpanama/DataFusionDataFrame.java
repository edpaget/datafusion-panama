package net.carcdr.datafusionpanama;

import java.lang.foreign.MemorySegment;

/** Manages the lifecycle of a DataFusion DataFrame. */
public interface DataFusionDataFrame extends AutoCloseable {

    /**
     * Returns the native pointer for use by other FFI classes.
     *
     * @throws IllegalStateException if the DataFrame has been closed
     */
    MemorySegment nativePointer();

    @Override
    void close();
}
