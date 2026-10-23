package com.minos.store;

import java.util.Objects;

/**
 * Neutral read view of one persisted snapshot, built once and reused across requests (ADR 0024, ADR 0056).
 *
 * <p>The persisted {@link CodeKnowledgeSnapshot} remains the source of truth; the {@link CodeKnowledgeReader} serves
 * the queries and carries no write. The incoming-relationship index is derived on first demand, once per view: from
 * the in-memory index when the reader is one (which already keeps it), otherwise from the snapshot. A class rather
 * than a record, because that index is state beyond the components.</p>
 */
public final class SnapshotReadView {

    private final SnapshotDescriptor descriptor;
    private final CodeKnowledgeSnapshot snapshot;
    private final CodeKnowledgeReader reader;
    private final long buildNanos;
    private final Object derivedLock = new Object();
    private volatile IncomingRelationships incoming;
    private long incomingBuilds;
    @SuppressWarnings("deprecation")
    private SnapshotQueryView legacy;

    public SnapshotReadView(
            SnapshotDescriptor descriptor,
            CodeKnowledgeSnapshot snapshot,
            CodeKnowledgeReader reader,
            long buildNanos
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.reader = Objects.requireNonNull(reader, "reader");
        if (buildNanos < 0) {
            throw new IllegalArgumentException("buildNanos must not be negative");
        }
        this.buildNanos = buildNanos;
    }

    /** Builds the in-memory index of a snapshot and returns its view; {@link #buildNanos()} is that build. */
    public static SnapshotReadView indexed(SnapshotDescriptor descriptor, CodeKnowledgeSnapshot snapshot) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(snapshot, "snapshot");
        long started = System.nanoTime();
        InMemoryCodeKnowledgeStore index = new InMemoryCodeKnowledgeStore(snapshot);
        return new SnapshotReadView(descriptor, snapshot, index, System.nanoTime() - started);
    }

    /**
     * Heap an owner of a cached view must charge for its incoming-relationship index, from the relationship count:
     * the index is derived after the cache entry is weighed, so its upper bound is charged up front.
     */
    public static long incomingRelationshipsWeightBytes(long relationshipCount) {
        return InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes(relationshipCount);
    }

    public SnapshotDescriptor descriptor() {
        return descriptor;
    }

    public CodeKnowledgeSnapshot snapshot() {
        return snapshot;
    }

    /** The reads of this snapshot, shared by every request; it exposes no write. */
    public CodeKnowledgeReader reader() {
        return reader;
    }

    public long buildNanos() {
        return buildNanos;
    }

    /** Relationships entering the symbols of the snapshot's project, built at most once for this view. */
    public IncomingRelationships incomingRelationships() {
        if (reader instanceof InMemoryCodeKnowledgeStore memory) {
            return memory.symbolEdgeIndex(snapshot.projectId().toString());
        }
        IncomingRelationships built = incoming;
        if (built != null) {
            return built;
        }
        synchronized (derivedLock) {
            if (incoming == null) {
                incoming = IncomingRelationships.of(snapshot);
                incomingBuilds++;
            }
            return incoming;
        }
    }

    /** How many incoming-relationship indexes this view built itself (never more than one). */
    long incomingRelationshipsBuilds() {
        synchronized (derivedLock) {
            return incomingBuilds;
        }
    }

    /** The legacy view of this read view, made on first demand and then shared (see {@link SnapshotQueryView#of}). */
    @SuppressWarnings("deprecation")
    SnapshotQueryView legacyView() {
        synchronized (derivedLock) {
            if (legacy == null) {
                legacy = SnapshotQueryView.derivedFrom(this);
            }
            return legacy;
        }
    }
}
