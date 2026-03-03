package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DataFusionSessionTest {

    @Test
    void createAndCloseCleanly() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create()) {
            try (DataFusionSession session = runtime.newSession()) {
                assertNotNull(session);
                assertNotNull(session.nativePointer());
            }
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
