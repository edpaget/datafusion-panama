package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecordBatchReaderTest {

    @Test
    void collectReturnsNonNullReader() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                RecordBatchReader reader = df.collect()) {
            assertNotNull(reader);
        }
    }

    @Test
    void collectSchemaIsNonNull() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                RecordBatchReader reader = df.collect()) {
            assertNotNull(reader.getSchema());
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
    void collectCurrentBatchIsNonNull() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS a");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            assertNotNull(reader.getCurrentBatch());
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
            assertNotNull(reader.getSchema());
            int batchCount = 0;
            while (reader.next()) {
                assertNotNull(reader.getCurrentBatch());
                batchCount++;
            }
            assertTrue(batchCount > 0, "should have at least one batch");
        }
    }
}
