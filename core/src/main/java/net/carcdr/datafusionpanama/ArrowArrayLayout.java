package net.carcdr.datafusionpanama;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Panama {@link StructLayout} for the Arrow C Data Interface {@code ArrowArray} struct.
 *
 * @see <a href="https://arrow.apache.org/docs/format/CDataInterface.html">Arrow C Data
 *     Interface</a>
 */
final class ArrowArrayLayout {

    static final StructLayout LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_LONG.withName("length"),
                    ValueLayout.JAVA_LONG.withName("null_count"),
                    ValueLayout.JAVA_LONG.withName("offset"),
                    ValueLayout.JAVA_LONG.withName("n_buffers"),
                    ValueLayout.JAVA_LONG.withName("n_children"),
                    ValueLayout.ADDRESS.withName("buffers"),
                    ValueLayout.ADDRESS.withName("children"),
                    ValueLayout.ADDRESS.withName("dictionary"),
                    ValueLayout.ADDRESS.withName("release"),
                    ValueLayout.ADDRESS.withName("private_data"));

    static final long SIZEOF = LAYOUT.byteSize();

    static final VarHandle LENGTH = varHandle("length");
    static final VarHandle NULL_COUNT = varHandle("null_count");
    static final VarHandle OFFSET = varHandle("offset");
    static final VarHandle N_BUFFERS = varHandle("n_buffers");
    static final VarHandle N_CHILDREN = varHandle("n_children");
    static final VarHandle BUFFERS = varHandle("buffers");
    static final VarHandle CHILDREN = varHandle("children");
    static final VarHandle DICTIONARY = varHandle("dictionary");
    static final VarHandle RELEASE = varHandle("release");
    static final VarHandle PRIVATE_DATA = varHandle("private_data");

    private ArrowArrayLayout() {}

    private static VarHandle varHandle(String name) {
        return LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(name));
    }
}
