package com.minos.query;

import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;

import java.util.Objects;
import java.util.Set;

/**
 * Résultat compact d'un usage résolu de symbole.
 */
public record UsageResult(
        String id,
        String projectId,
        String symbolId,
        SymbolLocation location,
        Set<OccurrenceRole> roles,
        ResolutionStatus resolutionStatus,
        Origin origin
) {
    public UsageResult {
        requireText(id, "id");
        requireText(projectId, "projectId");
        requireText(symbolId, "symbolId");
        Objects.requireNonNull(location, "location");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(origin, "origin");
    }

    public static UsageResult from(SymbolOccurrence occurrence) {
        Objects.requireNonNull(occurrence, "occurrence");
        return new UsageResult(
                occurrence.id(),
                occurrence.projectId(),
                occurrence.resolvedSymbolId().orElseThrow(() ->
                        new IllegalArgumentException("usage result requires a resolved symbol")),
                occurrence.location(),
                occurrence.roles(),
                occurrence.resolutionStatus(),
                occurrence.origin()
        );
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
