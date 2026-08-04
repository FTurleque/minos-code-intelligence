package com.minos.adapter.scip;

import java.nio.file.Path;
import java.util.Map;

/**
 * Contexte MINOS nécessaire pour normaliser un index SCIP.
 */
record ScipIngestionRequest(
        String projectId,
        String moduleId,
        String providerId,
        String providerVersion,
        String indexRunId,
        Map<String, String> fileIdsByRelativePath,
        String projectRelativeRoot) {

    ScipIngestionRequest {
        requireText(projectId, "projectId");
        requireText(providerId, "providerId");
        moduleId = blankToNull(moduleId);
        providerVersion = blankToNull(providerVersion);
        indexRunId = blankToNull(indexRunId);
        fileIdsByRelativePath = fileIdsByRelativePath == null
                ? Map.of()
                : Map.copyOf(fileIdsByRelativePath);
        projectRelativeRoot = normalizeRelativeRoot(projectRelativeRoot);
    }

    ScipIngestionRequest(
            String projectId,
            String moduleId,
            String providerId,
            String providerVersion,
            String indexRunId,
            Map<String, String> fileIdsByRelativePath
    ) {
        this(projectId, moduleId, providerId, providerVersion, indexRunId, fileIdsByRelativePath, "");
    }

    String explicitFileId(String relativePath) {
        return fileIdsByRelativePath.get(relativePath);
    }

    String projectRelativePath(String providerRelativePath) {
        String normalized = normalizeProviderRelativePath(providerRelativePath);
        if (normalized == null) {
            return providerRelativePath;
        }
        return projectRelativeRoot.isBlank() ? normalized : projectRelativeRoot + "/" + normalized;
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

    private static String normalizeProviderRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value.replace('\\', '/')).normalize();
            if (path.isAbsolute() || path.startsWith("..")) {
                return null;
            }
            return path.toString().replace('\\', '/');
        } catch (RuntimeException exception) {
            return null;
        }
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
