package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class DataFusionRuntimeTest {

    @Test
    void createReturnsNonNull() throws DataFusionException {
        try (DataFusionRuntime runtime = DataFusionRuntime.create()) {
            assertNotNull(runtime);
            assertNotNull(runtime.nativePointer());
        }
    }

    @Test
    void createAndCloseDoesNotThrow() throws DataFusionException {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        assertDoesNotThrow(runtime::close);
    }

    @Test
    void doubleCloseDoesNotThrow() throws DataFusionException {
        DataFusionRuntime runtime = DataFusionRuntime.create();
        runtime.close();
        assertDoesNotThrow(runtime::close);
    }
}
