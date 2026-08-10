package com.minos.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProbabilityInvariantTest {

    @Test
    void acceptsFiniteClosedUnitInterval() {
        assertDoesNotThrow(() -> ProbabilityInvariant.require(0.0, "confidence"));
        assertDoesNotThrow(() -> ProbabilityInvariant.require(0.5, "confidence"));
        assertDoesNotThrow(() -> ProbabilityInvariant.require(1.0, "confidence"));
        assertNull(ProbabilityInvariant.requireOptional(null, "confidence"));
    }

    @Test
    void rejectsNanInfinityAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ProbabilityInvariant.require(Double.NaN, "confidence"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbabilityInvariant.require(Double.POSITIVE_INFINITY, "confidence"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbabilityInvariant.require(Double.NEGATIVE_INFINITY, "confidence"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbabilityInvariant.require(-0.0001, "confidence"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbabilityInvariant.require(1.0001, "confidence"));
    }
}
