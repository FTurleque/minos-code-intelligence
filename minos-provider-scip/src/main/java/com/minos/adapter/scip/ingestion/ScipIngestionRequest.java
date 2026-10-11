package com.minos.adapter.scip.ingestion;

import java.nio.file.Path;
import java.util.Map;

import static com.minos.domain.Preconditions.requireText;

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
        String candidate = value.replace('\\', '/');
        Path path = Path.of(candidate).normalize();
        String portable = path.toString().replace('\\', '/');
        if (isRootedOnAnyPlatform(candidate) || isRootedOnAnyPlatform(portable)
                || path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("projectRelativeRoot must stay inside the registered project");
        }
        return ".".equals(portable) ? "" : portable;
    }

    /**
     * Forme portable d'un chemin écrit par un provider, ou {@code null} s'il ne peut pas être placé sous le
     * projet (vide, enraciné sur une plateforme, sortant, ou illisible pour l'hôte).
     */
    static String normalizeProviderRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String candidate = value.replace('\\', '/');
            Path path = Path.of(candidate).normalize();
            String portable = path.toString().replace('\\', '/');
            if (isRootedOnAnyPlatform(candidate) || isRootedOnAnyPlatform(portable)
                    || path.isAbsolute() || path.startsWith("..")) {
                return null;
            }
            return portable;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Vrai pour un chemin enraciné sur au moins une plateforme : séparateur initial (racine POSIX, chemin
     * Windows relatif au lecteur courant, UNC) ou lettre de lecteur. {@link Path#isAbsolute()} ne répond que
     * pour l'hôte : {@code /absolute} n'est pas absolu sous Windows, {@code C:/x} ne l'est pas sous Linux.
     */
    private static boolean isRootedOnAnyPlatform(String portable) {
        if (portable.startsWith("/")) {
            return true;
        }
        if (portable.length() < 2 || portable.charAt(1) != ':') {
            return false;
        }
        char drive = portable.charAt(0);
        return (drive >= 'a' && drive <= 'z') || (drive >= 'A' && drive <= 'Z');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
