package net.carcdr.datafusionpanama;

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
            try (DataFusionSession session = runtime.newSession()) {
                System.out.println("Session created: " + session.nativePointer());
                try (DataFusionDataFrame df = session.sql("SELECT 1 + 1 AS result")) {
                    System.out.println("DataFrame created: " + df.nativePointer());
                }
                System.out.println("DataFrame closed successfully.");
            }
            System.out.println("Session closed successfully.");
        }
        System.out.println("Runtime closed successfully.");
    }
}
