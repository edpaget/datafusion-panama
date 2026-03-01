package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public class Main {
    public static void main(String[] args) throws Throwable {
        MethodHandle add =
                NativeLibrary.downcallHandle(
                        "add",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        int result = (int) add.invokeExact(3, 4);
        System.out.println("3 + 4 = " + result);

        try (DataFusionRuntime runtime = DataFusionRuntime.create()) {
            System.out.println("Runtime created: " + runtime.nativePointer());
        }
        System.out.println("Runtime closed successfully.");
    }
}
