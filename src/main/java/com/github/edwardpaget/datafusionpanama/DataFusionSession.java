package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.MemorySegment;

/** Manages the lifecycle of a DataFusion session context. */
public interface DataFusionSession extends AutoCloseable {

    /**
     * Returns the native pointer for use by other FFI classes.
     *
     * @throws IllegalStateException if the session has been closed
     */
    MemorySegment nativePointer();

    @Override
    void close();
}
