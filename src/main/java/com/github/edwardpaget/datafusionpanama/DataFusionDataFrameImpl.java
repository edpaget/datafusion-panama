package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Package-private implementation of {@link DataFusionDataFrame}. */
final class DataFusionDataFrameImpl implements DataFusionDataFrame {

    private static final MethodHandle DATAFRAME_FREE =
            NativeLibrary.downcallHandle(
                    "dataframe_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private MemorySegment pointer;

    DataFusionDataFrameImpl(MemorySegment pointer) {
        this.pointer = pointer;
    }

    @Override
    public MemorySegment nativePointer() {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("DataFrame is closed");
        }
        return pointer;
    }

    @Override
    public void close() {
        if (pointer != null && !pointer.equals(MemorySegment.NULL)) {
            try {
                DATAFRAME_FREE.invokeExact(pointer);
            } catch (Throwable t) {
                throw new AssertionError("failed to free DataFrame", t);
            }
            pointer = MemorySegment.NULL;
        }
    }
}
