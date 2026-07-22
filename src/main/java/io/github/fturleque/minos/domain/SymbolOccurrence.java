package io.github.fturleque.minos.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Occurrence localisée d'une référence symbolique.
 *
 * <p>Une occurrence peut viser un symbole résolu ou une cible non résolue.
 * Les rôles forment un ensemble car certains fournisseurs, dont SCIP,
 * les représentent comme un bitset cumulable.</p>
 */
public record SymbolOccurrence(
        String id,
        String projectId,
        SymbolReference symbolRef,
        SymbolLocation location,
        Set<OccurrenceRole> roles,
        ResolutionStatus resolutionStatus,
        Origin origin,
        Set<ProviderReference> providerReferences) {

    public SymbolOccurrence {
        requireText(id, "id");
        requireText(projectId, "projectId");
        Objects.requireNonNull(symbolRef, "symbolRef");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(origin, "origin");

        roles = roles.isEmpty()
                ? Set.of(OccurrenceRole.OTHER)
                : Set.copyOf(EnumSet.copyOf(roles));
        providerReferences = providerReferences == null ? Set.of() : Set.copyOf(providerReferences);

        if (symbolRef instanceof ResolvedSymbolReference
                && resolutionStatus == ResolutionStatus.UNRESOLVED) {
            throw new IllegalArgumentException("resolved symbol reference cannot have UNRESOLVED status");
        }
        if (symbolRef instanceof UnresolvedSymbolReference
                && resolutionStatus == ResolutionStatus.RESOLVED) {
            throw new IllegalArgumentException("unresolved symbol reference cannot have RESOLVED status");
        }
    }

    public boolean hasRole(OccurrenceRole role) {
        return roles.contains(Objects.requireNonNull(role, "role"));
    }

    public boolean isDefinitionOccurrence() {
        return hasRole(OccurrenceRole.DEFINITION) || hasRole(OccurrenceRole.FORWARD_DEFINITION);
    }

    public Optional<String> resolvedSymbolId() {
        if (symbolRef instanceof ResolvedSymbolReference resolved) {
            return Optional.of(resolved.symbolId());
        }
        return Optional.empty();
    }

    public boolean isResolved() {
        return symbolRef instanceof ResolvedSymbolReference;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
