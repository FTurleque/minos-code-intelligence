package com.minos.store;

import java.util.Objects;

/**
 * Immutable query view built once for a persisted snapshot and reusable across requests.
 *
 * <p>The persisted {@link CodeKnowledgeSnapshot} remains the source of truth. Secondary
 * indexes are reconstructible derivatives owned by the in-memory query store.</p>
 */
public record SnapshotQueryView(
        SnapshotDescriptor descriptor,
        CodeKnowledgeSnapshot snapshot,
        InMemoryCodeKnowledgeStore queryStore,
        long buildNanos
) {
    public SnapshotQueryView {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(queryStore, "queryStore");
        if (buildNanos < 0) {
            throw new IllegalArgumentException("buildNanos must not be negative");
        }
    }

    public InMemoryCodeKnowledgeStore.IndexMetrics indexMetrics() {
        return queryStore.indexMetrics();
    }
}
