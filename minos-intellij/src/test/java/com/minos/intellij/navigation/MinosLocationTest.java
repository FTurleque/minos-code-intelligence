package com.minos.intellij.navigation;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void rejectsNegativeOrZeroBasedPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinosLocation("src/A.java", 0, 0, "UTF16_CODE_UNITS"));
        assertThrows(IllegalArgumentException.class,
                () -> new MinosLocation("src/A.java", 1, -1, "UTF16_CODE_UNITS"));
    }

    @Test
    void rejectsUnsupportedEncodingAndMalformedJson() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinosLocation("src/A.java", 1, 0, "UTF7_CODE_UNITS"));

        JsonObject missingLine = new JsonObject();
        missingLine.addProperty("fileId", "src/A.java");
        missingLine.addProperty("startColumn", 0);
        assertThrows(IllegalArgumentException.class, () -> MinosLocation.from(missingLine));

        JsonObject negativeColumn = new JsonObject();
        negativeColumn.addProperty("fileId", "src/A.java");
        negativeColumn.addProperty("startLine", 1);
        negativeColumn.addProperty("startColumn", -1);
        assertThrows(IllegalArgumentException.class, () -> MinosLocation.from(negativeColumn));
    }
}
