package com.minos.domain;

/** Shared invariant for confidence, probability and normalized weight values. */
public final class ProbabilityInvariant {

    private ProbabilityInvariant() {
    }

    public static double require(double value, String fieldName) {
        String field = fieldName == null || fieldName.isBlank() ? "value" : fieldName;
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
        return value;
    }

    public static Double requireOptional(Double value, String fieldName) {
        if (value != null) require(value, fieldName);
        return value;
    }
}
