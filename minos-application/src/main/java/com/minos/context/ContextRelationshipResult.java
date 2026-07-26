package com.minos.context;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.RelationshipDirection;
import com.minos.query.RelationshipResult;

import java.util.Objects;

/**
 * Relation rencontrée pendant une traversée M4, avec son ancre et sa profondeur.
 */
public record ContextRelationshipResult(
        int depth,
        CodeEntityRef anchor,
        RelationshipDirection direction,
        RelationshipResult relationship
) {
    public ContextRelationshipResult {
        if (depth < 1 || depth > CodeSearchCriteria.MAX_DEPTH) {
            throw new IllegalArgumentException("relationship depth is out of bounds");
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(relationship, "relationship");
    }
}
