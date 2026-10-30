package com.minos.store;

import java.util.Objects;

/**
 * Immutable query view built once for a persisted snapshot and reusable across requests.
 *
 * <p>The persisted {@link CodeKnowledgeSnapshot} remains the source of truth. Secondary
 * indexes are reconstructible derivatives owned by the in-memory query store.</p>
 *
 * @deprecated since 1.3.0: this view hands out the in-memory index and its writes. Read through
 * {@link SnapshotReadView}, obtained by {@link CodeKnowledgeSnapshotStore#loadActiveReadView} or {@link #readView()}
 * (ADR 0056). No removal is scheduled; removing it is a versioned break.
 */
@Deprecated(since = "1.3.0")
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

    /**
     * The legacy view of a read view, made once per read view and then returned again: its in-memory index when it
     * holds one, otherwise an index built from the snapshot (the only path that builds one).
     *
     * @deprecated since 1.3.0: for a store that still implements {@link CodeKnowledgeSnapshotStore#loadActiveQueryView}
     * from its read view. Use {@link SnapshotReadView} directly.
     */
    @Deprecated(since = "1.3.0")
    public static SnapshotQueryView of(SnapshotReadView view) {
        return Objects.requireNonNull(view, "view").legacyView();
    }

    static SnapshotQueryView derivedFrom(SnapshotReadView view) {
        if (view.reader() instanceof InMemoryCodeKnowledgeStore memory) {
            return new SnapshotQueryView(view.descriptor(), view.snapshot(), memory, view.buildNanos());
        }
        long started = System.nanoTime();
        InMemoryCodeKnowledgeStore memory = new InMemoryCodeKnowledgeStore(view.snapshot());
        return new SnapshotQueryView(view.descriptor(), view.snapshot(), memory, System.nanoTime() - started);
    }

    /**
     * The in-memory index, writes included.
     *
     * @deprecated since 1.3.0: read through {@link SnapshotReadView#reader()}, which carries no write.
     */
    @Deprecated(since = "1.3.0")
    @Override
    public InMemoryCodeKnowledgeStore queryStore() {
        return queryStore;
    }

    /**
     * Counters of the in-memory index.
     *
     * @deprecated since 1.3.0: the metrics belong to the in-memory index; no neutral metric replaces them.
     */
    @Deprecated(since = "1.3.0")
    public InMemoryCodeKnowledgeStore.IndexMetrics indexMetrics() {
        return queryStore.indexMetrics();
    }

    /** The neutral view of the same snapshot and the same index: nothing is rebuilt. */
    public SnapshotReadView readView() {
        return new SnapshotReadView(descriptor, snapshot, queryStore, buildNanos);
    }
}
