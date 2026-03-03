package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class ArrowArrayStreamLayoutTest {

    @Test
    void sizeIs40Bytes() {
        assertEquals(40, ArrowArrayStreamLayout.SIZEOF);
        assertEquals(40, ArrowArrayStreamLayout.LAYOUT.byteSize());
    }

    @Test
    void layoutHas5Fields() {
        long fieldCount =
                ArrowArrayStreamLayout.LAYOUT.memberLayouts().stream()
                        .filter(l -> l.name().isPresent())
                        .count();
        assertEquals(5, fieldCount);
    }

    @Test
    void fieldOffsetsMatchSpec() {
        assertEquals(0, offset("get_schema"));
        assertEquals(8, offset("get_next"));
        assertEquals(16, offset("get_last_error"));
        assertEquals(24, offset("release"));
        assertEquals(32, offset("private_data"));
    }

    @Test
    void functionPointerFieldsReadableAsAddress() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ArrowArrayStreamLayout.LAYOUT);
            seg.fill((byte) 0);

            MemorySegment sentinel = MemorySegment.ofAddress(0xDEAD);

            ArrowArrayStreamLayout.GET_SCHEMA.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayStreamLayout.GET_SCHEMA.get(seg, 0L));

            ArrowArrayStreamLayout.GET_NEXT.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayStreamLayout.GET_NEXT.get(seg, 0L));

            ArrowArrayStreamLayout.GET_LAST_ERROR.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayStreamLayout.GET_LAST_ERROR.get(seg, 0L));

            ArrowArrayStreamLayout.RELEASE.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayStreamLayout.RELEASE.get(seg, 0L));

            ArrowArrayStreamLayout.PRIVATE_DATA.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayStreamLayout.PRIVATE_DATA.get(seg, 0L));
        }
    }

    @Test
    void allVarHandlesAreNonNull() {
        assertNotNull(ArrowArrayStreamLayout.GET_SCHEMA);
        assertNotNull(ArrowArrayStreamLayout.GET_NEXT);
        assertNotNull(ArrowArrayStreamLayout.GET_LAST_ERROR);
        assertNotNull(ArrowArrayStreamLayout.RELEASE);
        assertNotNull(ArrowArrayStreamLayout.PRIVATE_DATA);
    }

    private static long offset(String name) {
        return ArrowArrayStreamLayout.LAYOUT.byteOffset(
                MemoryLayout.PathElement.groupElement(name));
    }
}
