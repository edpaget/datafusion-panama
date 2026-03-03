package net.carcdr.datafusionpanama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Package-private implementation of {@link RecordBatchReader}.
 *
 * <p>Owns an {@link Arena} that holds the {@code ArrowArrayStream}, {@code ArrowSchema}, and
 * current {@code ArrowArray} structs. Calls the stream's function pointers via Panama downcall
 * handles to iterate batches and release resources.
 */
final class RecordBatchReaderImpl implements RecordBatchReader {

    // MethodHandle for calling dataframe_collect via the native library
    private static final MethodHandle DATAFRAME_COLLECT =
            NativeLibrary.downcallHandle(
                    "dataframe_collect",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    // Downcall handles for ArrowArrayStream function pointers.
    // First argument is the function pointer itself (no bound address).
    private static final Linker LINKER = Linker.nativeLinker();

    // get_schema(stream, schema_out) -> int
    private static final MethodHandle GET_SCHEMA_MH =
            LINKER.downcallHandle(
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // get_next(stream, array_out) -> int
    private static final MethodHandle GET_NEXT_MH =
            LINKER.downcallHandle(
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // get_last_error(stream) -> const char*
    private static final MethodHandle GET_LAST_ERROR_MH =
            LINKER.downcallHandle(FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // release(stream) -> void
    private static final MethodHandle STREAM_RELEASE_MH =
            LINKER.downcallHandle(FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // release(schema_or_array) -> void
    private static final MethodHandle ARRAY_RELEASE_MH =
            LINKER.downcallHandle(FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private final Arena arena;
    private final MemorySegment streamSegment;
    private MemorySegment schemaSegment;
    private MemorySegment currentBatchSegment;
    private boolean closed;

    private RecordBatchReaderImpl(
            Arena arena, MemorySegment streamSegment, MemorySegment schemaSegment) {
        this.arena = arena;
        this.streamSegment = streamSegment;
        this.schemaSegment = schemaSegment;
        this.closed = false;
    }

    /**
     * Creates a new reader by calling {@code dataframe_collect} and loading the schema from the
     * resulting stream.
     */
    static RecordBatchReaderImpl create(MemorySegment runtimePtr, MemorySegment dataframePtr)
            throws DataFusionException {
        Arena readerArena = Arena.ofConfined();
        try {
            // Allocate the ArrowArrayStream struct
            MemorySegment stream = readerArena.allocate(ArrowArrayStreamLayout.LAYOUT);

            // Call dataframe_collect to populate the stream
            MemorySegment resultPtr;
            try {
                resultPtr =
                        (MemorySegment)
                                DATAFRAME_COLLECT.invokeExact(runtimePtr, dataframePtr, stream);
            } catch (Throwable t) {
                throw new AssertionError("unexpected FFI invocation error", t);
            }
            NativeLibrary.unwrapOrThrow(resultPtr);

            // Load the schema from the stream
            MemorySegment schema = readerArena.allocate(ArrowSchemaLayout.LAYOUT);
            MemorySegment getSchemaFn =
                    (MemorySegment) ArrowArrayStreamLayout.GET_SCHEMA.get(stream, 0L);
            int schemaStatus;
            try {
                schemaStatus = (int) GET_SCHEMA_MH.invokeExact(getSchemaFn, stream, schema);
            } catch (Throwable t) {
                throw new AssertionError("unexpected FFI invocation error calling get_schema", t);
            }
            if (schemaStatus != 0) {
                String error = getLastError(stream);
                readerArena.close();
                throw new DataFusionException(
                        "get_schema failed: " + (error != null ? error : "unknown error"));
            }

            return new RecordBatchReaderImpl(readerArena, stream, schema);
        } catch (DataFusionException e) {
            throw e;
        } catch (Throwable t) {
            readerArena.close();
            throw new AssertionError("unexpected error creating RecordBatchReader", t);
        }
    }

    @Override
    public boolean next() throws DataFusionException {
        if (closed) {
            throw new IllegalStateException("reader is closed");
        }

        // Release the previous batch if any
        releaseBatch();

        // Allocate a new ArrowArray struct for this batch
        currentBatchSegment = arena.allocate(ArrowArrayLayout.LAYOUT);

        MemorySegment getNextFn =
                (MemorySegment) ArrowArrayStreamLayout.GET_NEXT.get(streamSegment, 0L);
        int status;
        try {
            status = (int) GET_NEXT_MH.invokeExact(getNextFn, streamSegment, currentBatchSegment);
        } catch (Throwable t) {
            throw new AssertionError("unexpected FFI invocation error calling get_next", t);
        }
        if (status != 0) {
            String error = getLastError(streamSegment);
            throw new DataFusionException(
                    "get_next failed: " + (error != null ? error : "unknown error"));
        }

        // End-of-stream is signaled by release == NULL on the output ArrowArray
        MemorySegment releasePtr =
                (MemorySegment) ArrowArrayLayout.RELEASE.get(currentBatchSegment, 0L);
        if (releasePtr.equals(MemorySegment.NULL)) {
            currentBatchSegment = null;
            return false;
        }

        return true;
    }

    @Override
    public MemorySegment getSchema() {
        return schemaSegment;
    }

    @Override
    public MemorySegment getCurrentBatch() {
        return currentBatchSegment;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // Release current batch
        releaseBatch();

        // Release schema
        if (schemaSegment != null) {
            MemorySegment releasePtr =
                    (MemorySegment) ArrowSchemaLayout.RELEASE.get(schemaSegment, 0L);
            if (!releasePtr.equals(MemorySegment.NULL)) {
                try {
                    ARRAY_RELEASE_MH.invokeExact(releasePtr, schemaSegment);
                } catch (Throwable t) {
                    throw new AssertionError("failed to release schema", t);
                }
            }
            schemaSegment = null;
        }

        // Release stream
        MemorySegment streamReleaseFn =
                (MemorySegment) ArrowArrayStreamLayout.RELEASE.get(streamSegment, 0L);
        if (!streamReleaseFn.equals(MemorySegment.NULL)) {
            try {
                STREAM_RELEASE_MH.invokeExact(streamReleaseFn, streamSegment);
            } catch (Throwable t) {
                throw new AssertionError("failed to release stream", t);
            }
        }

        arena.close();
    }

    private void releaseBatch() {
        if (currentBatchSegment != null) {
            MemorySegment releasePtr =
                    (MemorySegment) ArrowArrayLayout.RELEASE.get(currentBatchSegment, 0L);
            if (!releasePtr.equals(MemorySegment.NULL)) {
                try {
                    ARRAY_RELEASE_MH.invokeExact(releasePtr, currentBatchSegment);
                } catch (Throwable t) {
                    throw new AssertionError("failed to release batch", t);
                }
            }
            currentBatchSegment = null;
        }
    }

    private static String getLastError(MemorySegment stream) {
        MemorySegment getLastErrorFn =
                (MemorySegment) ArrowArrayStreamLayout.GET_LAST_ERROR.get(stream, 0L);
        if (getLastErrorFn.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            MemorySegment msgPtr =
                    (MemorySegment) GET_LAST_ERROR_MH.invokeExact(getLastErrorFn, stream);
            if (msgPtr.equals(MemorySegment.NULL)) {
                return null;
            }
            return msgPtr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return null;
        }
    }
}
