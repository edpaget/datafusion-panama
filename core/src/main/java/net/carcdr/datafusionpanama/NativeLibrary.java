package net.carcdr.datafusionpanama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** Loads the native DataFusion library and provides helpers for FFI calls. */
final class NativeLibrary {

    static final SymbolLookup LOOKUP;
    private static final Linker LINKER = Linker.nativeLinker();

    static final MethodHandle RESULT_IS_OK;
    static final MethodHandle RESULT_UNWRAP;
    static final MethodHandle RESULT_ERROR_MESSAGE;
    static final MethodHandle RESULT_FREE;

    static {
        var libPath = System.getProperty("java.library.path");
        LOOKUP =
                SymbolLookup.libraryLookup(
                        Path.of(libPath, System.mapLibraryName("datafusion_panama")),
                        Arena.global());

        RESULT_IS_OK =
                downcallHandle(
                        "result_is_ok",
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        RESULT_UNWRAP =
                downcallHandle(
                        "result_unwrap",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        RESULT_ERROR_MESSAGE =
                downcallHandle(
                        "result_error_message",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        RESULT_FREE = downcallHandle("result_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private NativeLibrary() {}

    /** Creates a downcall {@link MethodHandle} for the given native function name. */
    static MethodHandle downcallHandle(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LOOKUP.find(name).orElseThrow(), descriptor);
    }

    /**
     * Inspects a {@code DFResult} pointer: returns the success value or throws {@link
     * DataFusionException}. The result is always freed.
     */
    static MemorySegment unwrapOrThrow(MemorySegment resultPtr) throws DataFusionException {
        try {
            boolean ok = (boolean) RESULT_IS_OK.invokeExact(resultPtr);
            if (ok) {
                MemorySegment value = (MemorySegment) RESULT_UNWRAP.invokeExact(resultPtr);
                RESULT_FREE.invokeExact(resultPtr);
                return value;
            }
            MemorySegment msgPtr = (MemorySegment) RESULT_ERROR_MESSAGE.invokeExact(resultPtr);
            String message = msgPtr.reinterpret(Long.MAX_VALUE).getString(0);
            RESULT_FREE.invokeExact(resultPtr);
            throw new DataFusionException(message);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }
}
