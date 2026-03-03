package net.carcdr.datafusionpanama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class ArrowSchemaLayoutTest {

    @Test
    void sizeIs72Bytes() {
        assertEquals(72, ArrowSchemaLayout.SIZEOF);
        assertEquals(72, ArrowSchemaLayout.LAYOUT.byteSize());
    }

    @Test
    void layoutHas9Fields() {
        long fieldCount =
                ArrowSchemaLayout.LAYOUT.memberLayouts().stream()
                        .filter(l -> l.name().isPresent())
                        .count();
        assertEquals(9, fieldCount);
    }

    @Test
    void fieldOffsetsMatchSpec() {
        assertEquals(0, offset("format"));
        assertEquals(8, offset("name"));
        assertEquals(16, offset("metadata"));
        assertEquals(24, offset("flags"));
        assertEquals(32, offset("n_children"));
        assertEquals(40, offset("children"));
        assertEquals(48, offset("dictionary"));
        assertEquals(56, offset("release"));
        assertEquals(64, offset("private_data"));
    }

    @Test
    void varHandlesCanReadAndWriteAddressFields() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ArrowSchemaLayout.LAYOUT);
            seg.fill((byte) 0);

            MemorySegment sentinel = MemorySegment.ofAddress(0xCAFE);
            ArrowSchemaLayout.FORMAT.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowSchemaLayout.FORMAT.get(seg, 0L));

            ArrowSchemaLayout.NAME.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowSchemaLayout.NAME.get(seg, 0L));

            ArrowSchemaLayout.PRIVATE_DATA.set(seg, 0L, sentinel);
            assertEquals(sentinel, ArrowSchemaLayout.PRIVATE_DATA.get(seg, 0L));
        }
    }

    @Test
    void varHandlesCanReadAndWriteIntegerFields() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ArrowSchemaLayout.LAYOUT);
            seg.fill((byte) 0);

            ArrowSchemaLayout.FLAGS.set(seg, 0L, 42L);
            assertEquals(42L, ArrowSchemaLayout.FLAGS.get(seg, 0L));

            ArrowSchemaLayout.N_CHILDREN.set(seg, 0L, 7L);
            assertEquals(7L, ArrowSchemaLayout.N_CHILDREN.get(seg, 0L));
        }
    }

    @Test
    void allVarHandlesAreNonNull() {
        assertNotNull(ArrowSchemaLayout.FORMAT);
        assertNotNull(ArrowSchemaLayout.NAME);
        assertNotNull(ArrowSchemaLayout.METADATA);
        assertNotNull(ArrowSchemaLayout.FLAGS);
        assertNotNull(ArrowSchemaLayout.N_CHILDREN);
        assertNotNull(ArrowSchemaLayout.CHILDREN);
        assertNotNull(ArrowSchemaLayout.DICTIONARY);
        assertNotNull(ArrowSchemaLayout.RELEASE);
        assertNotNull(ArrowSchemaLayout.PRIVATE_DATA);
    }

    private static long offset(String name) {
        return ArrowSchemaLayout.LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(name));
    }
}
