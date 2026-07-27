package com.minos.semantic;

import java.util.List;
import java.util.Objects;

/** Immutable embedding vector tied to one semantic document stable key. */
public record SemanticVector(String stableKey, List<Double> values) {
    public SemanticVector {
        if (stableKey == null || stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must not be blank");
        }
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("embedding values must be finite");
            }
        }
    }

    public int dimensions() {
        return values.size();
    }
}
