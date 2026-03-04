package net.carcdr.datafusionpanama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

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
    private static final MethodHandle SESSION_REGISTER_CSV =
            NativeLibrary.downcallHandle(
                    "session_register_csv",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private static final MethodHandle SESSION_REGISTER_PARQUET =
            NativeLibrary.downcallHandle(
                    "session_register_parquet",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
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
            return new DataFusionDataFrameImpl(dataframePtr, runtimePointer);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public void registerCsv(String tableName, Path path) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSegment = arena.allocateFrom(tableName);
            MemorySegment pathSegment = arena.allocateFrom(path.toString());
            MemorySegment resultPtr =
                    (MemorySegment)
                            SESSION_REGISTER_CSV.invokeExact(
                                    runtimePointer, nativePointer(), nameSegment, pathSegment);
            NativeLibrary.unwrapOrThrow(resultPtr);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error", t);
        }
    }

    @Override
    public void registerParquet(String tableName, Path path) throws DataFusionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSegment = arena.allocateFrom(tableName);
            MemorySegment pathSegment = arena.allocateFrom(path.toString());
            MemorySegment resultPtr =
                    (MemorySegment)
                            SESSION_REGISTER_PARQUET.invokeExact(
                                    runtimePointer, nativePointer(), nameSegment, pathSegment);
            NativeLibrary.unwrapOrThrow(resultPtr);
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
