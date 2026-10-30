package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Persistence-neutral active code-knowledge snapshot contract. */
public interface CodeKnowledgeSnapshotStore {

    SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) throws IOException;

    CodeKnowledgeSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols,
            Collection<SymbolOccurrence> occurrences,
            Collection<Relationship> relationships
    ) throws IOException;

    Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException;

    Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException;

    /**
     * Legacy view of the active snapshot, which hands out the in-memory index.
     *
     * @deprecated since 1.3.0: use {@link #loadActiveReadView(UUID)}. Still abstract, so that a store which
     * implements neither read cannot loop; a store that serves the read view implements it with
     * {@code loadActiveReadView(projectId).map(SnapshotQueryView::of)}.
     */
    @Deprecated(since = "1.3.0")
    Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException;

    /**
     * Neutral read view of the active snapshot (ADR 0056). The default derives it from
     * {@link #loadActiveQueryView(UUID)}, sharing its index; a store of the repository redefines it and caches the
     * read view itself.
     */
    @SuppressWarnings("deprecation")
    default Optional<SnapshotReadView> loadActiveReadView(UUID projectId) throws IOException {
        return loadActiveQueryView(projectId).map(SnapshotQueryView::readView);
    }

    /**
     * Identifier of the active snapshot, for callers that need nothing else. The default loads the
     * snapshot; a store that keeps an active pointer answers from it without reading or decoding the
     * snapshot.
     */
    default Optional<String> activeSnapshotId(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(CodeKnowledgeSnapshot::snapshotId);
    }
}
