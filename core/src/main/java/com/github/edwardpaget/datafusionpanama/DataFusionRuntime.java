package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.MemorySegment;

/** Manages the lifecycle of a Tokio runtime used by DataFusion. */
public interface DataFusionRuntime extends AutoCloseable {

    /** Creates a new DataFusion runtime backed by a multi-threaded Tokio executor. */
    static DataFusionRuntime create() throws DataFusionException {
        return DataFusionRuntimeImpl.create();
    }

    /** Creates a new DataFusion session context backed by this runtime. */
    DataFusionSession newSession() throws DataFusionException;

    /**
     * Returns the native pointer for use by other FFI classes.
     *
     * @throws IllegalStateException if the runtime has been closed
     */
    MemorySegment nativePointer();

    @Override
    void close();
}
