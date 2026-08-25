package com.minos.impact;

import com.minos.domain.InformationNature;
import com.minos.domain.ProbabilityInvariant;
import com.minos.domain.RelationshipKind;

import java.util.Objects;

/**
 * Étape explicable de propagation : une modification de {@code changedSymbolId}
 * peut potentiellement impacter {@code impactedSymbolId} via la relation source -> target observée.
 */
public record ImpactPathStep(
        String changedSymbolId,
        String impactedSymbolId,
        String relationshipId,
        RelationshipKind relationshipKind,
        InformationNature relationshipNature,
        double confidence
) {
    public ImpactPathStep {
        requireText(changedSymbolId, "changedSymbolId");
        requireText(impactedSymbolId, "impactedSymbolId");
        requireText(relationshipId, "relationshipId");
        Objects.requireNonNull(relationshipKind, "relationshipKind");
        Objects.requireNonNull(relationshipNature, "relationshipNature");
        ProbabilityInvariant.require(confidence, "confidence");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
