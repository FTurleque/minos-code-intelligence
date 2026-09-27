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

    Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException;
}
