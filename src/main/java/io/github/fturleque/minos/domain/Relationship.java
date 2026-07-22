package io.github.fturleque.minos.domain;

import java.util.List;
import java.util.Objects;

/**
 * Relation normalisée entre deux entités de code MINOS.
 */
public record Relationship(
        String id,
        CodeEntityRef source,
        CodeEntityRef target,
        String unresolvedTarget,
        RelationshipKind kind,
        SymbolLocation location,
        ResolutionStatus resolutionStatus,
        InformationNature nature,
        Double confidence,
        Origin origin,
        List<Evidence> evidence) {

    public Relationship {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(origin, "origin");

        if (target == null && (unresolvedTarget == null || unresolvedTarget.isBlank())) {
            throw new IllegalArgumentException("target or unresolvedTarget is required");
        }
        if (target != null && unresolvedTarget != null && !unresolvedTarget.isBlank()) {
            throw new IllegalArgumentException("target and unresolvedTarget are mutually exclusive");
        }
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
