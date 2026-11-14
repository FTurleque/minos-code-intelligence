package com.minos.semantic;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static com.minos.domain.Preconditions.requireText;

/**
 * A two-dimensional projection derived from the stored vectors of one semantic index. It names the index it was
 * computed from, how it was computed and which documents it shows: a reader decides from these alone whether the
 * projection still describes the index it looks at. Every point comes from that one index.
 *
 * @param index             the index the vectors were read from
 * @param method            how the coordinates were computed
 * @param selection         which documents were asked for
 * @param documentCount     documents of the index when the projection was prepared
 * @param candidateCount    documents of the selected kinds, of which {@code points} is a sample when smaller
 * @param explainedVariance share of the variance of the projected vectors carried by each axis, 0 to 1
 * @param points            the projected documents, in stable-key order, coordinates within -1 and 1
 */
public record SemanticProjection(
        IndexIdentity index,
        Method method,
        Selection selection,
        int documentCount,
        int candidateCount,
        List<Double> explainedVariance,
        List<Point> points
) {
    public static final int AXES = 2;

    public SemanticProjection {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(selection, "selection");
        explainedVariance = List.copyOf(Objects.requireNonNull(explainedVariance, "explainedVariance"));
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        if (explainedVariance.size() != AXES) {
            throw new IllegalArgumentException("explainedVariance must have one value per axis");
        }
        for (double share : explainedVariance) {
            if (!Double.isFinite(share) || share < 0.0 || share > 1.0) {
                throw new IllegalArgumentException("explainedVariance must be between 0 and 1");
            }
        }
        if (candidateCount < 0 || documentCount < candidateCount) {
            throw new IllegalArgumentException("candidateCount must be between 0 and documentCount");
        }
        if (points.size() > candidateCount || points.size() > selection.maxPoints()) {
            throw new IllegalArgumentException("a projection holds at most its selection");
        }
        String previous = null;
        for (Point point : points) {
            if (!selection.kinds().contains(point.kind())) {
                throw new IllegalArgumentException("a point is of a kind the selection does not hold");
            }
            if (previous != null && previous.compareTo(point.stableKey()) >= 0) {
                throw new IllegalArgumentException("points must be in strict stable-key order");
            }
            previous = point.stableKey();
        }
    }

    /** Whether the projection shows a sample of the documents of the selected kinds, not all of them. */
    public boolean sampled() {
        return points.size() < candidateCount;
    }

    /**
     * The index a projection was computed from. {@code documentsFingerprint} is the SHA-256 of the sorted stable
     * keys and checksums of its documents: two indexes with the same identity hold the same documents.
     */
    public record IndexIdentity(String projectId, String snapshotId, String providerId, String modelId,
                                int dimensions, String documentsFingerprint) {
        public IndexIdentity {
            requireText(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            requireText(modelId, "modelId");
            requireText(documentsFingerprint, "documentsFingerprint");
            if (dimensions < 1) throw new IllegalArgumentException("dimensions must be greater than zero");
        }
    }

    /** The method, its version, its parameters in one canonical text, and the seed of every random choice. */
    public record Method(String name, int version, String parameters, long seed) {
        public Method {
            requireText(name, "name");
            Objects.requireNonNull(parameters, "parameters");
            if (version < 1) throw new IllegalArgumentException("version must be greater than zero");
        }
    }

    /** The kinds of documents asked for and the largest number of points kept. */
    public record Selection(Set<SemanticDocumentKind> kinds, int maxPoints) {
        public Selection {
            Objects.requireNonNull(kinds, "kinds");
            if (kinds.isEmpty()) throw new IllegalArgumentException("kinds must not be empty");
            TreeSet<SemanticDocumentKind> ordered = new TreeSet<>(Comparator.comparing(Enum::name));
            ordered.addAll(kinds);
            kinds = java.util.Collections.unmodifiableSet(ordered);
            if (maxPoints < 1) throw new IllegalArgumentException("maxPoints must be greater than zero");
        }
    }

    /** One projected document: its stable key, its kind and its coordinates. */
    public record Point(String stableKey, SemanticDocumentKind kind, float x, float y) {
        public Point {
            requireText(stableKey, "stableKey");
            Objects.requireNonNull(kind, "kind");
            if (!inRange(x) || !inRange(y)) {
                throw new IllegalArgumentException("coordinates must be finite and between -1 and 1");
            }
        }

        private static boolean inRange(float value) {
            return Float.isFinite(value) && value >= -1.0f && value <= 1.0f;
        }
    }
}
