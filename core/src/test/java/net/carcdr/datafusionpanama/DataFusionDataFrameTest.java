package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DataFusionDataFrameTest {

    @Test
    void sqlReturnsNonNullDataFrame() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 1")) {
            assertNotNull(df);
            assertNotNull(df.nativePointer());
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
                DataFusionDataFrame df = session.sql("SELECT 1 + 1 AS result")) {
            assertNotNull(df);
            assertNotNull(df.nativePointer());
        }
    }
}
