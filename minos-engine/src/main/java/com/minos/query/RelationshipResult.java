package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.ProbabilityInvariant;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolLocation;

import java.util.List;
import java.util.Objects;

/**
 * Résultat compact d'une requête relationnelle M3.
 */
public record RelationshipResult(
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
        List<Evidence> evidence
) {
    public RelationshipResult {
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
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (nature != InformationNature.FACTUAL && confidence == null) {
            throw new IllegalArgumentException("derived or heuristic relationship requires confidence");
        }
        if (nature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived or heuristic relationship requires evidence");
        }
    }

    public static RelationshipResult from(Relationship relationship) {
        Objects.requireNonNull(relationship, "relationship");
        return new RelationshipResult(
                relationship.id(),
                relationship.projectId(),
                relationship.source(),
                relationship.target(),
                relationship.unresolvedTarget(),
                relationship.kind(),
                relationship.location(),
                relationship.resolutionStatus(),
                relationship.nature(),
                relationship.confidence(),
                relationship.origin(),
                relationship.evidence()
        );
    }
}
