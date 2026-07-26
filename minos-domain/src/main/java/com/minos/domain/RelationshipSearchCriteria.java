package com.minos.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Critères indépendants du backend pour rechercher des relations normalisées.
 */
public record RelationshipSearchCriteria(
        CodeEntityRef anchor,
        RelationshipDirection direction,
        Set<RelationshipKind> kinds,
        ResolutionStatus resolutionStatus,
        InformationNature nature,
        int limit
) {
    public RelationshipSearchCriteria {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(direction, "direction");
        kinds = kinds == null || kinds.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(kinds));
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
    }

    public static RelationshipSearchCriteria outgoing(
            CodeEntityRef anchor,
            Set<RelationshipKind> kinds,
            int limit
    ) {
        return new RelationshipSearchCriteria(
                anchor,
                RelationshipDirection.OUTGOING,
                kinds,
                null,
                null,
                limit
        );
    }

    public static RelationshipSearchCriteria incoming(
            CodeEntityRef anchor,
            Set<RelationshipKind> kinds,
            int limit
    ) {
        return new RelationshipSearchCriteria(
                anchor,
                RelationshipDirection.INCOMING,
                kinds,
                null,
                null,
                limit
        );
    }

    public static RelationshipSearchCriteria any(
            CodeEntityRef anchor,
            Set<RelationshipKind> kinds,
            int limit
    ) {
        return new RelationshipSearchCriteria(
                anchor,
                RelationshipDirection.ANY,
                kinds,
                null,
                null,
                limit
        );
    }
}
