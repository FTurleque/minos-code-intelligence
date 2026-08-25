package com.minos.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedTsvTest {
    @Test
    void splitsExactlyTheExpectedColumns() throws Exception {
        assertArrayEquals(new String[]{"a", "", "c"}, FixedTsv.splitExact("a\t\tc", 3, 7));
        assertEquals("a", FixedTsv.firstField("a\tb", 7));
    }

    @Test
    void rejectsExtraOrMissingColumnsBeforeFieldArrayExpansion() {
        assertThrows(IOException.class, () -> FixedTsv.splitExact("a\tb\tc", 2, 4));
        assertThrows(IOException.class, () -> FixedTsv.splitExact("a", 2, 4));
    }
}
