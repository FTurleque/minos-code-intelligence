package com.minos.semantic;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/** Immutable embedding vector tied to one semantic document stable key. */
public record SemanticVector(String stableKey, List<Double> values) {
    public SemanticVector {
        if (stableKey == null || stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must not be blank");
        }
        values = PrimitiveValues.copyOf(Objects.requireNonNull(values, "values"));
    }

    /** Builds an immutable vector without retaining one boxed {@link Double} object per dimension. */
    public static SemanticVector fromArray(String stableKey, double[] values) {
        return new SemanticVector(stableKey, PrimitiveValues.copyOf(Objects.requireNonNull(values, "values")));
    }

    public int dimensions() {
        return values.size();
    }

    /** Primitive accessor for hot similarity loops; the public List contract remains unchanged. */
    public double valueAt(int index) {
        return ((PrimitiveValues) values).valueAt(index);
    }

    /** Precomputed Euclidean norm used by exact cosine search. */
    public double norm() {
        return ((PrimitiveValues) values).norm();
    }

    private static double requireFloat32Compatible(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("embedding values must be finite");
        }
        if (!Float.isFinite((float) value)) {
            throw new IllegalArgumentException("embedding values must be representable as finite float32");
        }
        return value;
    }

    private static final class PrimitiveValues extends AbstractList<Double> implements RandomAccess {
        private final double[] values;
        private final double norm;

        private PrimitiveValues(double[] values, boolean clone) {
            if (values.length == 0) throw new IllegalArgumentException("values must not be empty");
            this.values = clone ? values.clone() : values;
            double squaredNorm = 0.0;
            for (double value : this.values) {
                requireFloat32Compatible(value);
                squaredNorm += value * value;
            }
            this.norm = Math.sqrt(squaredNorm);
        }

        static PrimitiveValues copyOf(List<Double> source) {
            if (source instanceof PrimitiveValues primitive) return primitive;
            if (source.isEmpty()) throw new IllegalArgumentException("values must not be empty");
            double[] compact = new double[source.size()];
            for (int i = 0; i < compact.length; i++) {
                Double value = source.get(i);
                if (value == null) {
                    throw new IllegalArgumentException("embedding values must be finite");
                }
                compact[i] = requireFloat32Compatible(value);
            }
            return new PrimitiveValues(compact, false);
        }

        static PrimitiveValues copyOf(double[] source) {
            return new PrimitiveValues(source, true);
        }

        double valueAt(int index) {
            return values[index];
        }

        double norm() {
            return norm;
        }

        @Override
        public Double get(int index) {
            return values[index];
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }
}
