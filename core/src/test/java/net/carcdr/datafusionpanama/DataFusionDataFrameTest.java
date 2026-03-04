package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.arrow.vector.BigIntVector;
import org.junit.jupiter.api.Test;

class DataFusionDataFrameTest {

    @Test
    void sqlReturnsDataFrameWithExpectedData() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 AS v");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("v");
            assertEquals(1L, vec.get(0));
        }
    }

    @Test
    void sqlWithInvalidQueryThrowsException() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            assertThrows(DataFusionException.class, () -> session.sql("NOT VALID SQL AT ALL %%%"));
        }
    }

    @Test
    void dataFrameDoubleCloseDoesNotThrow() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            DataFusionDataFrame df = session.sql("SELECT 1");
            df.close();
            assertDoesNotThrow(df::close);
        }
    }

    @Test
    void nativePointerAfterCloseThrows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession()) {
            DataFusionDataFrame df = session.sql("SELECT 1");
            df.close();
            assertThrows(IllegalStateException.class, df::nativePointer);
        }
    }

    @Test
    void sqlWithSelectExpression() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1 + 1 AS result");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("result");
            assertEquals(2L, vec.get(0));
        }
    }
}
