package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Package-private implementation of {@link DataFusionSession}. */
final class DataFusionSessionImpl implements DataFusionSession {

    private static final MethodHandle SESSION_NEW =
            NativeLibrary.downcallHandle(
                    "session_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SESSION_FREE =
            NativeLibrary.downcallHandle(
                    "session_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private MemorySegment pointer;

    private DataFusionSessionImpl(MemorySegment pointer) {
        this.pointer = pointer;
    }

    static DataFusionSession create(MemorySegment runtimePtr) throws DataFusionException {
        try {
            MemorySegment resultPtr = (MemorySegment) SESSION_NEW.invokeExact(runtimePtr);
            MemorySegment sessionPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionSessionImpl(sessionPtr);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public MemorySegment nativePointer() {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("session is closed");
        }
        return pointer;
    }

    @Override
    public void close() {
        if (pointer != null && !pointer.equals(MemorySegment.NULL)) {
            try {
                SESSION_FREE.invokeExact(pointer);
            } catch (Throwable t) {
                throw new AssertionError("failed to free session", t);
            }
            pointer = MemorySegment.NULL;
        }
    }
}
