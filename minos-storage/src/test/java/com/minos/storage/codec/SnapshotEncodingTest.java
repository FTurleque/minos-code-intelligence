package com.minos.storage.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotEncodingTest {
    private static final String SHA = "a".repeat(64);

    @Test
    void refusesAMissingOrBlankChecksum() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCodec.SnapshotEncoding(null, 0, 0, 0));
        assertEquals("sha256 must not be blank", missing.getMessage());
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCodec.SnapshotEncoding("  ", 0, 0, 0));
        assertEquals("sha256 must not be blank", blank.getMessage());
    }

    @Test
    void refusesEachNegativeCount() {
        assertNegativeRefused(-1, 0, 0);
        assertNegativeRefused(0, -1, 0);
        assertNegativeRefused(0, 0, -1);
    }

    @Test
    void acceptsEmptySnapshotCounts() {
        SnapshotCodec.SnapshotEncoding encoding = new SnapshotCodec.SnapshotEncoding(SHA, 0, 0, 0);
        assertEquals(SHA, encoding.sha256());
        assertEquals(0, encoding.symbolCount() + encoding.occurrenceCount() + encoding.relationshipCount());
    }

    private static void assertNegativeRefused(int symbols, int occurrences, int relationships) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCodec.SnapshotEncoding(SHA, symbols, occurrences, relationships));
        assertEquals("snapshot counts must not be negative", failure.getMessage());
    }
}
