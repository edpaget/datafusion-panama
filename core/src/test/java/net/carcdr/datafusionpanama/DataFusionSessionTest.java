package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.arrow.vector.BigIntVector;
import org.junit.jupiter.api.Test;

class DataFusionSessionTest {

    @Test
    void sessionCanExecuteQuery() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create();
                DataFusionSession session = runtime.newSession();
                DataFusionDataFrame df = session.sql("SELECT 42 AS answer");
                RecordBatchReader reader = df.collect()) {
            assertTrue(reader.next());
            BigIntVector vec = (BigIntVector) reader.getCurrentBatch().getVector("answer");
            assertEquals(42L, vec.get(0));
        }
    }

    @Test
    void doubleCloseDoesNotThrow() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create()) {
            DataFusionSession session = runtime.newSession();
            session.close();
            assertDoesNotThrow(session::close);
        }
    }

    @Test
    void nativePointerAfterCloseThrows() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create()) {
            DataFusionSession session = runtime.newSession();
            session.close();
            assertThrows(IllegalStateException.class, session::nativePointer);
        }
    }
}
