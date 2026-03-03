package net.carcdr.datafusionpanama;

import java.lang.foreign.MemorySegment;

/** Manages the lifecycle of a Tokio runtime used by DataFusion. */
public interface DataFusionRuntime extends AutoCloseable {

    /**
     * Creates a new DataFusion runtime backed by a multi-threaded Tokio executor.
     *
     * @return a new runtime
     * @throws DataFusionException if the runtime cannot be created
     */
    static DataFusionRuntime create() throws DataFusionException {
        return DataFusionRuntimeImpl.create();
    }

    /**
     * Creates a new DataFusion session context backed by this runtime.
     *
     * @return a new session
     * @throws DataFusionException if the session cannot be created
     */
    DataFusionSession newSession() throws DataFusionException;

    /**
     * Returns the native pointer for use by other FFI classes.
     *
     * @return the native memory segment holding the pointer
     * @throws IllegalStateException if the runtime has been closed
     */
    MemorySegment nativePointer();

    @Override
    void close();
}
