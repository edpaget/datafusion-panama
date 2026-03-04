package net.carcdr.datafusionpanama;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Iterates over Arrow record batches received from a DataFusion DataFrame via the Arrow C Data
 * Interface {@code ArrowArrayStream}.
 *
 * <p>Uses Arrow Java's C Data Interface support for zero-copy import of record batches. The
 * returned {@link Schema} and {@link VectorSchemaRoot} provide typed column access via Arrow Java's
 * {@code FieldVector} subclasses (e.g. {@code BigIntVector}, {@code VarCharVector}).
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
     * Returns the schema of the stream.
     *
     * @return the Arrow schema describing the columns in the stream
     */
    Schema getSchema();

    /**
     * Returns the current record batch as a {@link VectorSchemaRoot}.
     *
     * <p>Only valid after {@link #next()} has returned {@code true}. The returned root is valid
     * until the next call to {@link #next()} or {@link #close()}.
     *
     * @return the current record batch
     */
    VectorSchemaRoot getCurrentBatch();

    @Override
    void close();
}
