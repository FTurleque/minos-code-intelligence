package io.github.fturleque.minos.domain;

import java.util.Objects;

/**
 * Occurrence d'un symbole dans un fichier source.
 *
 * <p>Un symbole décrit une déclaration logique ; une occurrence décrit un emplacement concret
 * où ce symbole est défini, référencé, appelé ou utilisé.</p>
 */
public record SymbolOccurrence(
        String id,
        String projectId,
        String symbolId,
        SymbolLocation location,
        OccurrenceRole role,
        ResolutionStatus resolutionStatus,
        Origin origin) {

    public SymbolOccurrence {
        requireText(id, "id");
        requireText(projectId, "projectId");
        requireText(symbolId, "symbolId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(origin, "origin");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
