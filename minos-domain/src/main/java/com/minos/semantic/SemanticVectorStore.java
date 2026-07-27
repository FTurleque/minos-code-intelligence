package com.minos.semantic;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reconstructible local vector index abstraction. Snapshots remain authoritative. */
public interface SemanticVectorStore {

    Optional<IndexSnapshot> load(String projectId) throws IOException;

    void replace(IndexSnapshot snapshot) throws IOException;

    void delete(String projectId) throws IOException;

    record IndexedDocument(SemanticDocument document, SemanticVector vector) {
        public IndexedDocument {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(vector, "vector");
            if (!document.stableKey().equals(vector.stableKey())) {
                throw new IllegalArgumentException("document/vector stableKey mismatch");
            }
        }
    }

    record IndexSnapshot(
            String projectId,
            String snapshotId,
            String providerId,
            String modelId,
            int dimensions,
            long builtAtEpochMilli,
            List<IndexedDocument> documents
    ) {
        public IndexSnapshot {
            requireText(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            requireText(modelId, "modelId");
            if (dimensions < 1) throw new IllegalArgumentException("dimensions must be greater than zero");
            if (builtAtEpochMilli < 0) throw new IllegalArgumentException("builtAtEpochMilli must not be negative");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            for (IndexedDocument value : documents) {
                if (!projectId.equals(value.document().projectId())) {
                    throw new IllegalArgumentException("semantic document belongs to another project");
                }
                if (!snapshotId.equals(value.document().snapshotId())) {
                    throw new IllegalArgumentException("semantic document belongs to another snapshot");
                }
                if (value.vector().dimensions() != dimensions) {
                    throw new IllegalArgumentException("semantic vector dimensions mismatch");
                }
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
