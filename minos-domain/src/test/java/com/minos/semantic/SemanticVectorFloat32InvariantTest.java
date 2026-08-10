package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVectorFloat32InvariantTest {

    @Test
    void acceptsFiniteValuesRepresentableByFloat32() {
        SemanticVector vector = new SemanticVector(
                "symbol:boundary",
                List.of((double) Float.MAX_VALUE, (double) -Float.MAX_VALUE, 0.0, 1.0 / 3.0));

        assertEquals(4, vector.dimensions());
        assertEquals((double) Float.MAX_VALUE, vector.valueAt(0));
        assertTrue(Double.isFinite(vector.norm()));
    }

    @Test
    void rejectsFiniteDoubleValuesThatOverflowFloat32() {
        double positiveOverflow = (double) Float.MAX_VALUE * 2.0;
        double negativeOverflow = -positiveOverflow;

        IllegalArgumentException positive = assertThrows(
                IllegalArgumentException.class,
                () -> new SemanticVector("symbol:positive-overflow", List.of(positiveOverflow)));
        IllegalArgumentException negative = assertThrows(
                IllegalArgumentException.class,
                () -> SemanticVector.fromArray("symbol:negative-overflow", new double[]{negativeOverflow}));

        assertTrue(positive.getMessage().contains("float32"));
        assertTrue(negative.getMessage().contains("float32"));
    }

    @Test
    void stillRejectsNonFiniteDoubleValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVector("symbol:nan", List.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVector("symbol:positive-infinity", List.of(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVector("symbol:negative-infinity", List.of(Double.NEGATIVE_INFINITY)));
    }
}
