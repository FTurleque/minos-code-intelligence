package com.minos.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SymbolLocationTest {

    @Test
    void rejectsReversedColumnsOnSameLine() {
        assertThrows(IllegalArgumentException.class, () -> new SymbolLocation(
                "src/Main.java", 4, 12, 4, 3, PositionEncoding.UTF16_CODE_UNITS));
    }

    @Test
    void permitsLowerEndColumnOnLaterLine() {
        assertDoesNotThrow(() -> new SymbolLocation(
                "src/Main.java", 4, 12, 5, 3, PositionEncoding.UTF16_CODE_UNITS));
    }

    @Test
    void rejectsInvalidLinesAndNegativeColumns() {
        assertThrows(IllegalArgumentException.class, () -> new SymbolLocation(
                "src/Main.java", 0, 0, 1, 0, PositionEncoding.UTF16_CODE_UNITS));
        assertThrows(IllegalArgumentException.class, () -> new SymbolLocation(
                "src/Main.java", 3, -1, 3, 1, PositionEncoding.UTF16_CODE_UNITS));
    }

    @Test
    void requiresPositionEncoding() {
        assertThrows(NullPointerException.class, () -> new SymbolLocation(
                "src/Main.java", 1, 0, 1, 0, null));
    }
}
