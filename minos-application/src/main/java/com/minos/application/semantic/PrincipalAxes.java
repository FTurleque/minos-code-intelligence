package com.minos.application.semantic;

import com.minos.semantic.SemanticVector;

import java.util.SplittableRandom;

/**
 * The two principal axes of a set of vectors, found without ever holding the set: each step is one pass that adds
 * the vectors one after the other. The working memory is a few arrays of the size of one vector.
 *
 * <p>Order of use: {@link #addToMean} for every vector, {@link #endMean}; then, until {@link #endIteration}
 * answers true or the caller stops, {@link #addToIteration} for every vector; then {@link #project} for every
 * vector and {@link #finish}. Vectors are given in the same order at every pass: the sums, and so the result,
 * are the same from one run to the next, whatever the machine.</p>
 */
final class PrincipalAxes {

    /** Below this agreement between two successive bases, another pass is worth its cost. */
    static final double TOLERANCE = 1e-9;
    /** Below this share of its length, what is left of a vector once its part along another is removed is noise. */
    private static final double DEGENERATE = 1e-12;
    private static final double NEGLIGIBLE_VARIANCE = 1e-18;

    private final int dimensions;
    private final double[] mean;
    private final double[] centered;
    private double[] first;
    private double[] second;
    private double[] nextFirst;
    private double[] nextSecond;
    private long count;
    private double squareFirst;
    private double squareSecond;
    private double cross;
    private double total;

    PrincipalAxes(int dimensions, long seed) {
        this.dimensions = dimensions;
        this.mean = new double[dimensions];
        this.centered = new double[dimensions];
        this.first = new double[dimensions];
        this.second = new double[dimensions];
        this.nextFirst = new double[dimensions];
        this.nextSecond = new double[dimensions];
        SplittableRandom random = new SplittableRandom(seed);
        for (int index = 0; index < dimensions; index++) first[index] = random.nextDouble() - 0.5;
        for (int index = 0; index < dimensions; index++) second[index] = random.nextDouble() - 0.5;
        orthonormalize(first, second);
    }

    /** Bytes of the arrays this computation holds for vectors of {@code dimensions} values. */
    static long workingBytes(int dimensions) {
        return 6L * dimensions * Double.BYTES;
    }

    void addToMean(SemanticVector vector) {
        for (int index = 0; index < dimensions; index++) mean[index] += vector.valueAt(index);
        count++;
    }

    void endMean() {
        if (count == 0) return;
        for (int index = 0; index < dimensions; index++) mean[index] /= count;
    }

    void addToIteration(SemanticVector vector) {
        center(vector);
        double onFirst = dot(centered, first);
        double onSecond = dot(centered, second);
        for (int index = 0; index < dimensions; index++) {
            nextFirst[index] += onFirst * centered[index];
            nextSecond[index] += onSecond * centered[index];
        }
    }

    /** Ends one pass; true when the basis no longer moves, or cannot move because the vectors do not spread. */
    boolean endIteration() {
        boolean spread = norm(nextFirst) > 0.0;
        if (!spread) {
            java.util.Arrays.fill(nextFirst, 0.0);
            java.util.Arrays.fill(nextSecond, 0.0);
            return true;
        }
        orthonormalize(nextFirst, nextSecond);
        boolean still = Math.abs(dot(first, nextFirst)) >= 1.0 - TOLERANCE
                && (norm(nextSecond) == 0.0 || Math.abs(dot(second, nextSecond)) >= 1.0 - TOLERANCE);
        double[] previousFirst = first;
        double[] previousSecond = second;
        first = nextFirst;
        second = nextSecond;
        nextFirst = previousFirst;
        nextSecond = previousSecond;
        java.util.Arrays.fill(nextFirst, 0.0);
        java.util.Arrays.fill(nextSecond, 0.0);
        return still;
    }

    /** Writes the two raw coordinates of {@code vector} at {@code 2 * position} of {@code coordinates}. */
    void project(SemanticVector vector, double[] coordinates, int position) {
        center(vector);
        double onFirst = dot(centered, first);
        double onSecond = dot(centered, second);
        coordinates[2 * position] = onFirst;
        coordinates[2 * position + 1] = onSecond;
        squareFirst += onFirst * onFirst;
        squareSecond += onSecond * onSecond;
        cross += onFirst * onSecond;
        total += dot(centered, centered);
    }

    /**
     * Turns the raw coordinates into the final ones, in place: rotated onto the two principal axes of the basis,
     * each axis signed so that the first non-zero value of its direction is positive, and both divided by one
     * factor so that the farthest point touches the square from -1 to 1. Returns the share of the variance each
     * axis carries. Vectors that do not spread are all at the origin.
     */
    double[] finish(double[] coordinates, int points) {
        double meanSquare = dot(mean, mean);
        if (total <= NEGLIGIBLE_VARIANCE * points * Math.max(meanSquare, Double.MIN_NORMAL)) {
            java.util.Arrays.fill(coordinates, 0, 2 * points, 0.0);
            return new double[]{0.0, 0.0};
        }
        double angle = 0.5 * StrictMath.atan2(2.0 * cross, squareFirst - squareSecond);
        double cosine = StrictMath.cos(angle);
        double sine = StrictMath.sin(angle);
        double major = squareFirst * cosine * cosine + 2.0 * cross * cosine * sine + squareSecond * sine * sine;
        double minor = squareFirst * sine * sine - 2.0 * cross * cosine * sine + squareSecond * cosine * cosine;
        double signMajor = signOf(cosine, sine);
        double signMinor = signOf(-sine, cosine);
        double farthest = 0.0;
        for (int position = 0; position < points; position++) {
            double onFirst = coordinates[2 * position];
            double onSecond = coordinates[2 * position + 1];
            double x = signMajor * (onFirst * cosine + onSecond * sine);
            double y = signMinor * (-onFirst * sine + onSecond * cosine);
            coordinates[2 * position] = x;
            coordinates[2 * position + 1] = y;
            farthest = Math.max(farthest, Math.max(Math.abs(x), Math.abs(y)));
        }
        if (farthest > 0.0) {
            for (int index = 0; index < 2 * points; index++) coordinates[index] /= farthest;
        }
        return new double[]{share(major), share(minor)};
    }

    private double share(double variance) {
        return Math.max(0.0, Math.min(1.0, variance / total));
    }

    /** +1 or -1 so that the first non-zero value of {@code a * first + b * second} becomes positive. */
    private double signOf(double a, double b) {
        for (int index = 0; index < dimensions; index++) {
            double value = a * first[index] + b * second[index];
            if (value > 0.0) return 1.0;
            if (value < 0.0) return -1.0;
        }
        return 1.0;
    }

    private void center(SemanticVector vector) {
        for (int index = 0; index < dimensions; index++) centered[index] = vector.valueAt(index) - mean[index];
    }

    /** Makes {@code a} a unit vector and {@code b} a unit vector orthogonal to it, or zero when it has no part left. */
    private static void orthonormalize(double[] a, double[] b) {
        scale(a, norm(a));
        double whole = norm(b);
        double along = dot(b, a);
        for (int index = 0; index < b.length; index++) b[index] -= along * a[index];
        double rest = norm(b);
        if (rest <= DEGENERATE * whole) java.util.Arrays.fill(b, 0.0);
        else scale(b, rest);
    }

    private static void scale(double[] values, double norm) {
        if (norm == 0.0) return;
        for (int index = 0; index < values.length; index++) values[index] /= norm;
    }

    private static double norm(double[] values) {
        return Math.sqrt(dot(values, values));
    }

    private static double dot(double[] left, double[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) sum += left[index] * right[index];
        return sum;
    }
}
