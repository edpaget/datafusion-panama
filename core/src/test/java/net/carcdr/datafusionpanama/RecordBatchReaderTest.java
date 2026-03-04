package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.arrow.vector.BigIntVector;
import org.junit.jupiter.api.Test;

class RecordBatchReaderTest {

    @Test
    void collectSchemaHasExpectedField() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                RecordBatchReader reader = df.collect()) {
            assertEquals(1, reader.getSchema().getFields().size());
            assertEquals("a", reader.getSchema().getFields().get(0).getName());
        }
    }

    @Test
    void collectNextReturnsTrueThenFalse() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next(), "first next() should return true");
            assertFalse(reader.next(), "second next() should return false (stream exhausted)");
        }
    }

    @Test
    void collectCurrentBatchContainsExpectedValue() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            var batch = reader.getCurrentBatch();
            assertEquals(1, batch.getRowCount());
            BigIntVector vec = (BigIntVector) batch.getVector("a");
            assertEquals(1L, vec.get(0));
        }
    }

    @Test
    void collectReaderDoubleCloseDoesNotThrow() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a")) {
            RecordBatchReader reader = df.collect();
            reader.close();
            assertDoesNotThrow(reader::close);
        }
    }

    @Test
    void collectFullRoundTrip() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a, 2 AS b");
                RecordBatchReader reader = df.collect()) {
            assertEquals(2, reader.getSchema().getFields().size());
            assertTrue(reader.next());
            var batch = reader.getCurrentBatch();
            assertEquals(1, batch.getRowCount());
            BigIntVector a = (BigIntVector) batch.getVector("a");
            BigIntVector b = (BigIntVector) batch.getVector("b");
            assertEquals(1L, a.get(0));
            assertEquals(2L, b.get(0));
            assertFalse(reader.next());
        }
    }
}
