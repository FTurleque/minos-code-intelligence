package com.minos.intellij.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinosLocationTest {

    @Test
    void keepsUtf16ColumnsAsEditorOffsets() {
        MinosLocation location = new MinosLocation("src/A.java", 3, 4, "UTF16_CODE_UNITS");
        assertEquals(4, location.utf16Column("abcdef"));
    }

    @Test
    void convertsUtf32CodePointsToUtf16Offsets() {
        MinosLocation location = new MinosLocation("src/A.java", 1, 2, "UTF32_CODE_UNITS");
        assertEquals(3, location.utf16Column("a😀b"));
    }

    @Test
    void convertsUtf8BytesToUtf16Offsets() {
        MinosLocation location = new MinosLocation("src/A.java", 1, 5, "UTF8_CODE_UNITS");
        assertEquals(3, location.utf16Column("a😀b"));
    }

    @Test
    void clampsColumnsPastEndOfLine() {
        MinosLocation location = new MinosLocation("src/A.java", 1, 99, "UTF16_CODE_UNITS");
        assertEquals(3, location.utf16Column("abc"));
    }
}
