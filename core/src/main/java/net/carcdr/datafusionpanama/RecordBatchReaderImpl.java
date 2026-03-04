package net.carcdr.datafusionpanama;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.apache.arrow.c.ArrowArrayStream;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Package-private implementation of {@link RecordBatchReader}.
 *
 * <p>Uses Arrow Java's C Data Interface to import an {@code ArrowArrayStream} produced by
 * DataFusion into an {@link ArrowReader}, providing zero-copy access to typed Arrow vectors.
 */
final class RecordBatchReaderImpl implements RecordBatchReader {

    private static final MethodHandle DATAFRAME_COLLECT =
            NativeLibrary.downcallHandle(
                    "dataframe_collect",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

    private final BufferAllocator allocator;
    private final ArrowReader arrowReader;
    private boolean closed;

    private RecordBatchReaderImpl(BufferAllocator allocator, ArrowReader arrowReader) {
        this.allocator = allocator;
        this.arrowReader = arrowReader;
        this.closed = false;
    }

    /**
     * Creates a new reader by calling {@code dataframe_collect} and importing the resulting {@code
     * ArrowArrayStream} via Arrow Java.
     */
    static RecordBatchReaderImpl create(MemorySegment runtimePtr, MemorySegment dataframePtr)
            throws DataFusionException {
        BufferAllocator alloc = new RootAllocator();
        try {
            ArrowArrayStream stream = ArrowArrayStream.allocateNew(alloc);
            try {
                // Get the memory address of the Arrow C stream struct and pass it to Rust
                long addr = stream.memoryAddress();
                MemorySegment streamSegment = MemorySegment.ofAddress(addr);

                MemorySegment resultPtr;
                try {
                    resultPtr =
                            (MemorySegment)
                                    DATAFRAME_COLLECT.invokeExact(
                                            runtimePtr, dataframePtr, streamSegment);
                } catch (Throwable t) {
                    throw new AssertionError("unexpected FFI invocation error", t);
                }
                NativeLibrary.unwrapOrThrow(resultPtr);

                // Import the populated stream into an ArrowReader
                ArrowReader reader = Data.importArrayStream(alloc, stream);
                return new RecordBatchReaderImpl(alloc, reader);
            } catch (Throwable t) {
                stream.close();
                throw t;
            }
        } catch (DataFusionException e) {
            alloc.close();
            throw e;
        } catch (Throwable t) {
            alloc.close();
            throw new AssertionError("unexpected error creating RecordBatchReader", t);
        }
    }

    @Override
    public boolean next() throws DataFusionException {
        if (closed) {
            throw new IllegalStateException("reader is closed");
        }
        try {
            return arrowReader.loadNextBatch();
        } catch (IOException e) {
            throw new DataFusionException("failed to load next batch: " + e.getMessage(), e);
        }
    }

    @Override
    public Schema getSchema() {
        if (closed) {
            throw new IllegalStateException("reader is closed");
        }
        try {
            return arrowReader.getVectorSchemaRoot().getSchema();
        } catch (IOException e) {
            throw new AssertionError("failed to get schema", e);
        }
    }

    @Override
    public VectorSchemaRoot getCurrentBatch() {
        if (closed) {
            throw new IllegalStateException("reader is closed");
        }
        try {
            return arrowReader.getVectorSchemaRoot();
        } catch (IOException e) {
            throw new AssertionError("failed to get current batch", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            arrowReader.close();
        } catch (IOException e) {
            throw new AssertionError("failed to close ArrowReader", e);
        } finally {
            allocator.close();
        }
    }
}
