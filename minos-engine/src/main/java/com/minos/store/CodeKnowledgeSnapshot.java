package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Snapshot immuable M3 regroupant symboles, occurrences et relations d'un projet.
 */
public record CodeKnowledgeSnapshot(
        UUID projectId,
        String snapshotId,
        List<Symbol> symbols,
        List<SymbolOccurrence> occurrences,
        List<Relationship> relationships
) {
    public CodeKnowledgeSnapshot {
        Objects.requireNonNull(projectId, "projectId");
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
        relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));

        String expectedProjectId = projectId.toString();
        if (symbols.stream().anyMatch(symbol -> !expectedProjectId.equals(symbol.projectId()))) {
            throw new IllegalArgumentException("every symbol must belong to snapshot project " + projectId);
        }
        if (occurrences.stream().anyMatch(occurrence ->
                !expectedProjectId.equals(occurrence.projectId()))) {
            throw new IllegalArgumentException("every occurrence must belong to snapshot project " + projectId);
        }
        if (relationships.stream().anyMatch(relationship ->
                !expectedProjectId.equals(relationship.projectId()))) {
            throw new IllegalArgumentException("every relationship must belong to snapshot project " + projectId);
        }

        requireUniqueIds(symbols, Symbol::id, "symbol");
        requireUniqueIds(occurrences, SymbolOccurrence::id, "occurrence");
        requireUniqueIds(relationships, Relationship::id, "relationship");
    }

    private static <T> void requireUniqueIds(List<T> values, Function<T, String> idExtractor, String kind) {
        Set<String> ids = new HashSet<>();
        for (T value : values) {
            String id = Objects.requireNonNull(idExtractor.apply(value), kind + " id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate " + kind + " id in snapshot: " + id);
            }
        }
    }
}
