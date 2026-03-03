package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.Arena;
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
    private static final MethodHandle SESSION_SQL =
            NativeLibrary.downcallHandle(
                    "session_sql",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private final MemorySegment runtimePointer;
    private MemorySegment pointer;

    private DataFusionSessionImpl(MemorySegment pointer, MemorySegment runtimePointer) {
        this.pointer = pointer;
        this.runtimePointer = runtimePointer;
    }

    static DataFusionSession create(MemorySegment runtimePtr) throws DataFusionException {
        try {
            MemorySegment resultPtr = (MemorySegment) SESSION_NEW.invokeExact(runtimePtr);
            MemorySegment sessionPtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionSessionImpl(sessionPtr, runtimePtr);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionDataFrame sql(String query) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sqlSegment = arena.allocateFrom(query);
            MemorySegment resultPtr =
                    (MemorySegment)
                            SESSION_SQL.invokeExact(runtimePointer, nativePointer(), sqlSegment);
            MemorySegment dataframePtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionDataFrameImpl(dataframePtr);
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
