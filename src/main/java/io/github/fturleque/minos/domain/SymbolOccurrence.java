package io.github.fturleque.minos.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Occurrence d'un symbole dans un fichier source.
 *
 * <p>Un symbole décrit une déclaration logique ; une occurrence décrit un emplacement concret
 * où ce symbole est défini, référencé, appelé ou utilisé. Les rôles forment un ensemble car
 * certains fournisseurs, dont SCIP, les représentent comme un bitset cumulable.</p>
 */
public record SymbolOccurrence(
        String id,
        String projectId,
        String symbolId,
        SymbolLocation location,
        Set<OccurrenceRole> roles,
        ResolutionStatus resolutionStatus,
        Origin origin,
        Set<ProviderReference> providerReferences) {

    public SymbolOccurrence {
        requireText(id, "id");
        requireText(projectId, "projectId");
        requireText(symbolId, "symbolId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(origin, "origin");

        roles = roles.isEmpty()
                ? Set.of(OccurrenceRole.OTHER)
                : Set.copyOf(EnumSet.copyOf(roles));
        providerReferences = providerReferences == null ? Set.of() : Set.copyOf(providerReferences);
    }

    public boolean hasRole(OccurrenceRole role) {
        return roles.contains(Objects.requireNonNull(role, "role"));
    }

    public boolean isDefinitionOccurrence() {
        return hasRole(OccurrenceRole.DEFINITION) || hasRole(OccurrenceRole.FORWARD_DEFINITION);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
