package io.github.fturleque.minos.domain;

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
        if (weight != null && (weight < 0.0 || weight > 1.0)) {
            throw new IllegalArgumentException("weight must be between 0 and 1");
        }
    }
}
