package com.minos.domain;

import java.util.Objects;

/**
 * Preuve structurée associée à un fait, une dérivation ou une heuristique.
 */
public record Evidence(
        EvidenceType type,
        String description,
        CodeEntityRef source,
        CodeEntityRef target,
        SymbolLocation location,
        Double weight) {

    public Evidence {
        Objects.requireNonNull(type, "type");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        ProbabilityInvariant.requireOptional(weight, "weight");
    }
}
