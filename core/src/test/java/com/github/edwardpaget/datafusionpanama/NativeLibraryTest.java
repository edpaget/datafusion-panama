package com.github.edwardpaget.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.Test;

class NativeLibraryTest {

    @Test
    void libraryLoadsSuccessfully() {
        assertNotNull(NativeLibrary.LOOKUP);
    }

    @Test
    void canCallAddFunction() throws Throwable {
        MethodHandle add =
                NativeLibrary.downcallHandle(
                        "add",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int result = (int) add.invokeExact(3, 4);
        assertEquals(7, result);
    }

    @Test
    void unwrapOrThrowExtractsValue() throws Throwable {
        MethodHandle runtimeNew =
                NativeLibrary.downcallHandle(
                        "runtime_new", FunctionDescriptor.of(ValueLayout.ADDRESS));
        MemorySegment resultPtr = (MemorySegment) runtimeNew.invokeExact();
        MemorySegment value = NativeLibrary.unwrapOrThrow(resultPtr);
        assertNotEquals(MemorySegment.NULL, value);

        // Clean up the runtime
        MethodHandle runtimeFree =
                NativeLibrary.downcallHandle(
                        "runtime_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        runtimeFree.invokeExact(value);
    }
}
