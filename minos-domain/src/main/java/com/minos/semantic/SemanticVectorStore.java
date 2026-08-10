package com.minos.semantic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/** Reconstructible semantic vector index abstraction. Snapshots remain authoritative. */
public interface SemanticVectorStore {

    Optional<IndexSnapshot> load(String projectId) throws IOException;
    void replace(IndexSnapshot snapshot) throws IOException;
    void delete(String projectId) throws IOException;

    /**
     * Loads only index metadata when the backend can do so cheaply. The default preserves
     * compatibility for simple/local stores by deriving metadata from the full snapshot.
     */
    default Optional<IndexMetadata> metadata(String projectId) throws IOException {
        return load(projectId).map(IndexMetadata::from);
    }

    /** Stable diagnostic identifier for the vector ranking engine. */
    default String searchEngine() { return "exact-linear"; }

    /**
     * Executes vector ranking for one already-embedded query. The local backend keeps the
     * historical exact cosine scan; database backends may push ranking into their vector engine.
     */
    default List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore) throws IOException {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (!Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("minimumScore must be between -1 and 1");
        }
        Optional<IndexSnapshot> loaded = load(projectId);
        if (loaded.isEmpty()) return List.of();
        IndexSnapshot index = loaded.orElseThrow();
        if (query.dimensions() != index.dimensions()) throw new IllegalArgumentException("vector dimensions mismatch");

        Comparator<VectorHit> bestFirst = Comparator.comparingDouble(VectorHit::score).reversed()
                .thenComparing(hit -> hit.document().stableKey());
        Comparator<VectorHit> worstFirst = bestFirst.reversed();
        PriorityQueue<VectorHit> top = new PriorityQueue<>(limit, worstFirst);
        for (IndexedDocument value : index.documents()) {
            double denominator = query.norm() * value.vector().norm();
            double score = 0.0;
            if (denominator != 0.0) {
                double dot = 0.0;
                for (int i = 0; i < query.dimensions(); i++) dot += query.valueAt(i) * value.vector().valueAt(i);
                score = Math.max(-1.0, Math.min(1.0, dot / denominator));
            }
            if (score < minimumScore) continue;
            VectorHit candidate = new VectorHit(value.document(), score);
            if (top.size() < limit) top.add(candidate);
            else if (bestFirst.compare(candidate, top.peek()) < 0) {
                top.poll(); top.add(candidate);
            }
        }
        List<VectorHit> hits = new ArrayList<>(top);
        hits.sort(bestFirst);
        return List.copyOf(hits);
    }

    default long sizeBytes(String projectId) throws IOException {
        Optional<IndexSnapshot> value = load(projectId);
        if (value.isEmpty()) return 0L;
        long bytes = 0L;
        for (IndexedDocument document : value.orElseThrow().documents()) {
            bytes += document.document().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            bytes += (long) document.vector().dimensions() * Float.BYTES;
        }
        return bytes;
    }

    record IndexMetadata(
            String projectId,
            String snapshotId,
            String providerId,
            String modelId,
            int dimensions,
            long builtAtEpochMilli,
            int documentCount
    ) {
        public IndexMetadata {
            requireText(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            requireText(modelId, "modelId");
            if (dimensions < 1) throw new IllegalArgumentException("dimensions must be greater than zero");
            if (builtAtEpochMilli < 0L) throw new IllegalArgumentException("builtAtEpochMilli must not be negative");
            if (documentCount < 0) throw new IllegalArgumentException("documentCount must not be negative");
        }

        static IndexMetadata from(IndexSnapshot snapshot) {
            return new IndexMetadata(
                    snapshot.projectId(), snapshot.snapshotId(), snapshot.providerId(), snapshot.modelId(),
                    snapshot.dimensions(), snapshot.builtAtEpochMilli(), snapshot.documents().size());
        }
    }

    record VectorHit(SemanticDocument document, double score) {
        public VectorHit {
            Objects.requireNonNull(document, "document");
            if (!Double.isFinite(score) || score < -1.0 || score > 1.0) throw new IllegalArgumentException("score must be finite and between -1 and 1");
        }
    }

    record IndexedDocument(SemanticDocument document, SemanticVector vector) {
        public IndexedDocument {
            Objects.requireNonNull(document, "document"); Objects.requireNonNull(vector, "vector");
            if (!document.stableKey().equals(vector.stableKey())) throw new IllegalArgumentException("document/vector stableKey mismatch");
        }
    }

    record IndexSnapshot(String projectId, String snapshotId, String providerId, String modelId,
                         int dimensions, long builtAtEpochMilli, List<IndexedDocument> documents) {
        public IndexSnapshot {
            requireText(projectId, "projectId"); requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId"); requireText(modelId, "modelId");
            if (dimensions < 1) throw new IllegalArgumentException("dimensions must be greater than zero");
            if (builtAtEpochMilli < 0) throw new IllegalArgumentException("builtAtEpochMilli must not be negative");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            for (IndexedDocument value : documents) {
                if (!projectId.equals(value.document().projectId())) throw new IllegalArgumentException("semantic document belongs to another project");
                if (!snapshotId.equals(value.document().snapshotId())) throw new IllegalArgumentException("semantic document belongs to another snapshot");
                if (value.vector().dimensions() != dimensions) throw new IllegalArgumentException("semantic vector dimensions mismatch");
            }
        }
        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
