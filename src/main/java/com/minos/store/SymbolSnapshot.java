package com.minos.store;

import com.minos.domain.Symbol;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot immuable des symboles normalisés d'un projet.
 */
public record SymbolSnapshot(
        UUID projectId,
        String snapshotId,
        List<Symbol> symbols
) {
    public SymbolSnapshot {
        Objects.requireNonNull(projectId, "projectId");
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        String expectedProjectId = projectId.toString();
        if (symbols.stream().anyMatch(symbol -> !expectedProjectId.equals(symbol.projectId()))) {
            throw new IllegalArgumentException("every symbol must belong to snapshot project " + projectId);
        }
    }
}
