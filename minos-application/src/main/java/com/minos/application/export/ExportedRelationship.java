package com.minos.application.export;

import com.minos.domain.Relationship;

import java.util.Objects;

/**
 * A relationship retained by the export, between two exported symbols, with the project-relative path of its location
 * (the path of its source symbol when it has no location).
 */
public record ExportedRelationship(Relationship relationship, String filePath) {

    public ExportedRelationship {
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(filePath, "filePath");
    }
}
