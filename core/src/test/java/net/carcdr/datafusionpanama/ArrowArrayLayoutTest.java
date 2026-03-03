package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class ArrowArrayLayoutTest {

    @Test
    void sizeIs80Bytes() {
        assertEquals(80, ArrowArrayLayout.SIZEOF);
        assertEquals(80, ArrowArrayLayout.LAYOUT.byteSize());
    }

    @Test
    void layoutHas10Fields() {
        long fieldCount =
                ArrowArrayLayout.LAYOUT.memberLayouts().stream()
                        .filter(l -> l.name().isPresent())
                        .count();
        assertEquals(10, fieldCount);
    }

    @Test
    void fieldOffsetsMatchSpec() {
        assertEquals(0, offset("length"));
        assertEquals(8, offset("null_count"));
        assertEquals(16, offset("offset"));
        assertEquals(24, offset("n_buffers"));
        assertEquals(32, offset("n_children"));
        assertEquals(40, offset("buffers"));
        assertEquals(48, offset("children"));
        assertEquals(56, offset("dictionary"));
        assertEquals(64, offset("release"));
        assertEquals(72, offset("private_data"));
    }

    @Test
    void varHandlesCanReadAndWriteIntegerFields() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ArrowArrayLayout.LAYOUT);
            seg.fill((byte) 0);

            ArrowArrayLayout.LENGTH.set(seg, 0L, 100L);
            assertEquals(100L, ArrowArrayLayout.LENGTH.get(seg, 0L));

            ArrowArrayLayout.NULL_COUNT.set(seg, 0L, 5L);
            assertEquals(5L, ArrowArrayLayout.NULL_COUNT.get(seg, 0L));

            ArrowArrayLayout.OFFSET.set(seg, 0L, 10L);
            assertEquals(10L, ArrowArrayLayout.OFFSET.get(seg, 0L));

            ArrowArrayLayout.N_BUFFERS.set(seg, 0L, 3L);
            assertEquals(3L, ArrowArrayLayout.N_BUFFERS.get(seg, 0L));

            ArrowArrayLayout.N_CHILDREN.set(seg, 0L, 2L);
            assertEquals(2L, ArrowArrayLayout.N_CHILDREN.get(seg, 0L));
        }
    }

    @Test
    void varHandlesCanReadAndWriteAddressFields() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ArrowArrayLayout.LAYOUT);
            seg.fill((byte) 0);

            MemorySegment sentinel = MemorySegment.ofAddress(0xBEEF);
            ArrowArrayLayout.BUFFERS.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayLayout.BUFFERS.get(seg, 0L));

            ArrowArrayLayout.CHILDREN.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayLayout.CHILDREN.get(seg, 0L));

            ArrowArrayLayout.PRIVATE_DATA.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowArrayLayout.PRIVATE_DATA.get(seg, 0L));
        }
    }

    @Test
    void allVarHandlesAreNonNull() {
        assertNotNull(ArrowArrayLayout.LENGTH);
        assertNotNull(ArrowArrayLayout.NULL_COUNT);
        assertNotNull(ArrowArrayLayout.OFFSET);
        assertNotNull(ArrowArrayLayout.N_BUFFERS);
        assertNotNull(ArrowArrayLayout.N_CHILDREN);
        assertNotNull(ArrowArrayLayout.BUFFERS);
        assertNotNull(ArrowArrayLayout.CHILDREN);
        assertNotNull(ArrowArrayLayout.DICTIONARY);
        assertNotNull(ArrowArrayLayout.RELEASE);
        assertNotNull(ArrowArrayLayout.PRIVATE_DATA);
    }

    private static long offset(String name) {
        return ArrowArrayLayout.LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(name));
    }
}
