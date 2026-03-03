package net.carcdr.datafusionpanama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Package-private implementation of {@link DataFusionRuntime}. */
final class DataFusionRuntimeImpl implements DataFusionRuntime {

    private static final MethodHandle RUNTIME_NEW =
            NativeLibrary.downcallHandle("runtime_new", FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle RUNTIME_FREE =
            NativeLibrary.downcallHandle(
                    "runtime_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private MemorySegment pointer;

    private DataFusionRuntimeImpl(MemorySegment pointer) {
        this.pointer = pointer;
    }

    static DataFusionRuntime create() throws DataFusionException {
        try {
            MemorySegment resultPtr = (MemorySegment) RUNTIME_NEW.invokeExact();
            MemorySegment runtimePtr = NativeLibrary.unwrapOrThrow(resultPtr);
            return new DataFusionRuntimeImpl(runtimePtr);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public DataFusionSession newSession() throws DataFusionException {
        return DataFusionSessionImpl.create(nativePointer());
    }

    @Override
    public MemorySegment nativePointer() {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("runtime is closed");
        }
        return pointer;
    }

    @Override
    public void close() {
        if (pointer != null && !pointer.equals(MemorySegment.NULL)) {
            try {
                RUNTIME_FREE.invokeExact(pointer);
            } catch (Throwable t) {
                throw new AssertionError("failed to free runtime", t);
            }
            pointer = MemorySegment.NULL;
        }
    }
}
