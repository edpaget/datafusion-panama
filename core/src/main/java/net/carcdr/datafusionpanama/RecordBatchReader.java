package net.carcdr.datafusionpanama;

import java.lang.foreign.MemorySegment;

/**
 * Iterates over Arrow record batches received from a DataFusion DataFrame via the Arrow C Data
 * Interface {@code ArrowArrayStream}.
 *
 * <p>The returned {@link MemorySegment} values for schema and batches point to raw {@code
 * ArrowSchema} and {@code ArrowArray} structs respectively. Higher-level typed access is provided
 * by a future layer.
 */
public interface RecordBatchReader extends AutoCloseable {

    /**
     * Advances the reader to the next record batch.
     *
     * @return {@code true} if a batch is available via {@link #getCurrentBatch()}, {@code false} if
     *     the stream is exhausted
     * @throws DataFusionException if the underlying stream reports an error
     */
    boolean next() throws DataFusionException;

    /**
     * Returns the schema of the stream as a raw {@code ArrowSchema} memory segment.
     *
     * <p>The segment is valid for the lifetime of this reader.
     */
    MemorySegment getSchema();

    /**
     * Returns the current record batch as a raw {@code ArrowArray} memory segment.
     *
     * <p>Only valid after {@link #next()} has returned {@code true}. The segment is valid until the
     * next call to {@link #next()} or {@link #close()}.
     */
    MemorySegment getCurrentBatch();

    @Override
    void close();
}
