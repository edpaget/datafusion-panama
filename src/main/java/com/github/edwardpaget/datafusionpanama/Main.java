package com.github.edwardpaget.datafusionpanama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Throwable {
        var libPath = System.getProperty("java.library.path");
        try (var arena = Arena.ofConfined()) {
            SymbolLookup lookup =
                    SymbolLookup.libraryLookup(
                            Path.of(libPath, System.mapLibraryName("datafusion_panama")), arena);

            Linker linker = Linker.nativeLinker();
            MethodHandle add =
                    linker.downcallHandle(
                            lookup.find("add").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT));

            int result = (int) add.invokeExact(3, 4);
            System.out.println("3 + 4 = " + result);
        }
    }
}
