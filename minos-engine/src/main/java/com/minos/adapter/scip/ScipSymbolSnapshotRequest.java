package com.minos.adapter.scip;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Contexte nécessaire pour publier la connaissance normalisée d'un index SCIP
 * dans un snapshot persistant MINOS.
 */
public record ScipSymbolSnapshotRequest(
        UUID projectId,
        String snapshotId,
        String moduleId,
        String providerId,
        String providerVersion,
        String indexRunId,
        Map<String, String> fileIdsByRelativePath,
        String projectRelativeRoot
) {
    public ScipSymbolSnapshotRequest {
        Objects.requireNonNull(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        requireText(providerId, "providerId");
        moduleId = blankToNull(moduleId);
        providerVersion = blankToNull(providerVersion);
        indexRunId = blankToNull(indexRunId);
        fileIdsByRelativePath = fileIdsByRelativePath == null
                ? Map.of()
                : Map.copyOf(fileIdsByRelativePath);
        projectRelativeRoot = normalizeRelativeRoot(projectRelativeRoot);
    }

    /** Compatibility constructor for historical project-root SCIP imports. */
    public ScipSymbolSnapshotRequest(
            UUID projectId,
            String snapshotId,
            String moduleId,
            String providerId,
            String providerVersion,
            String indexRunId,
            Map<String, String> fileIdsByRelativePath
    ) {
        this(projectId, snapshotId, moduleId, providerId, providerVersion, indexRunId,
                fileIdsByRelativePath, "");
    }

    private static String normalizeRelativeRoot(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Path path = Path.of(value.replace('\\', '/')).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("projectRelativeRoot must stay inside the registered project");
        }
        String portable = path.toString().replace('\\', '/');
        return ".".equals(portable) ? "" : portable;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
