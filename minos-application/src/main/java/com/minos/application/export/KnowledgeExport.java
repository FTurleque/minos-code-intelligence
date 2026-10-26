package com.minos.application.export;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The bounded export of the active knowledge snapshot of one registered project: symbols and relationships ordered by
 * identifier, and the limitations of the selection in the order they were found.
 */
public record KnowledgeExport(
        String projectId,
        String displayName,
        Path rootPath,
        String snapshotId,
        List<ExportedSymbol> symbols,
        List<ExportedRelationship> relationships,
        List<String> limitations
) {

    public KnowledgeExport {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(rootPath, "rootPath");
        Objects.requireNonNull(snapshotId, "snapshotId");
        symbols = List.copyOf(symbols);
        relationships = List.copyOf(relationships);
        limitations = List.copyOf(limitations);
    }
}
