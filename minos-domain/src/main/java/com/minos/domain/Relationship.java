package com.minos.domain;

import java.util.List;
import java.util.Objects;

/**
 * Relation normalisée entre deux entités de code MINOS.
 */
public record Relationship(
        String id,
        String projectId,
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
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
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
        if (target == null && resolutionStatus == ResolutionStatus.RESOLVED) {
            throw new IllegalArgumentException("unresolved target cannot have RESOLVED status");
        }
        if (target != null && resolutionStatus == ResolutionStatus.UNRESOLVED) {
            throw new IllegalArgumentException("resolved target cannot have UNRESOLVED status");
        }
        ProbabilityInvariant.requireOptional(confidence, "confidence");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (nature != InformationNature.FACTUAL && confidence == null) {
            throw new IllegalArgumentException("derived or heuristic relationship requires confidence");
        }
        if (nature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived or heuristic relationship requires evidence");
        }
    }
}
