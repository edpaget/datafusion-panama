package com.github.edwardpaget.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class DataFusionExceptionTest {

    @Test
    void carriesMessage() {
        var ex = new DataFusionException("something failed");
        assertEquals("something failed", ex.getMessage());
    }

    @Test
    void isCheckedException() {
        assertInstanceOf(Exception.class, new DataFusionException("test"));
        assertFalse(RuntimeException.class.isAssignableFrom(DataFusionException.class));
    }
}
