package com.minos.adapter.scip;

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
        Map<String, String> fileIdsByRelativePath) {

    ScipIngestionRequest {
        requireText(projectId, "projectId");
        requireText(providerId, "providerId");
        moduleId = blankToNull(moduleId);
        providerVersion = blankToNull(providerVersion);
        indexRunId = blankToNull(indexRunId);
        fileIdsByRelativePath = fileIdsByRelativePath == null
                ? Map.of()
                : Map.copyOf(fileIdsByRelativePath);
    }

    String explicitFileId(String relativePath) {
        return fileIdsByRelativePath.get(relativePath);
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
