package net.carcdr.datafusionpanama;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Panama {@link StructLayout} for the Arrow C Data Interface {@code ArrowArrayStream} struct.
 *
 * @see <a href="https://arrow.apache.org/docs/format/CDataInterface.html">Arrow C Data
 *     Interface</a>
 */
final class ArrowArrayStreamLayout {

    static final StructLayout LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.ADDRESS.withName("get_schema"),
                    ValueLayout.ADDRESS.withName("get_next"),
                    ValueLayout.ADDRESS.withName("get_last_error"),
                    ValueLayout.ADDRESS.withName("release"),
                    ValueLayout.ADDRESS.withName("private_data"));

    static final long SIZEOF = LAYOUT.byteSize();

    static final VarHandle GET_SCHEMA = varHandle("get_schema");
    static final VarHandle GET_NEXT = varHandle("get_next");
    static final VarHandle GET_LAST_ERROR = varHandle("get_last_error");
    static final VarHandle RELEASE = varHandle("release");
    static final VarHandle PRIVATE_DATA = varHandle("private_data");

    private ArrowArrayStreamLayout() {}

    private static VarHandle varHandle(String name) {
        return LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(name));
    }
}
