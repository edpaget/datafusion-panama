package net.carcdr.datafusionpanama;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Panama {@link StructLayout} for the Arrow C Data Interface {@code ArrowSchema} struct.
 *
 * @see <a href="https://arrow.apache.org/docs/format/CDataInterface.html">Arrow C Data
 *     Interface</a>
 */
final class ArrowSchemaLayout {

    static final StructLayout LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.ADDRESS.withName("format"),
                    ValueLayout.ADDRESS.withName("name"),
                    ValueLayout.ADDRESS.withName("metadata"),
                    ValueLayout.JAVA_LONG.withName("flags"),
                    ValueLayout.JAVA_LONG.withName("n_children"),
                    ValueLayout.ADDRESS.withName("children"),
                    ValueLayout.ADDRESS.withName("dictionary"),
                    ValueLayout.ADDRESS.withName("release"),
                    ValueLayout.ADDRESS.withName("private_data"));

    static final long SIZEOF = LAYOUT.byteSize();

    static final VarHandle FORMAT = varHandle("format");
    static final VarHandle NAME = varHandle("name");
    static final VarHandle METADATA = varHandle("metadata");
    static final VarHandle FLAGS = varHandle("flags");
    static final VarHandle N_CHILDREN = varHandle("n_children");
    static final VarHandle CHILDREN = varHandle("children");
    static final VarHandle DICTIONARY = varHandle("dictionary");
    static final VarHandle RELEASE = varHandle("release");
    static final VarHandle PRIVATE_DATA = varHandle("private_data");

    private ArrowSchemaLayout() {}

    private static VarHandle varHandle(String name) {
        return LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(name));
    }
}
