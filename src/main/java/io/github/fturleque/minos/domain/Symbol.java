package io.github.fturleque.minos.domain;

import java.util.Objects;

/**
 * Déclaration adressable dans le modèle de connaissance MINOS.
 */
public record Symbol(
        String id,
        String symbolKey,
        String projectId,
        String moduleId,
        String fileId,
        String parentSymbolId,
        SymbolKind kind,
        String name,
        String qualifiedName,
        String signature,
        String language,
        SymbolLocation location,
        ResolutionStatus resolutionStatus,
        Origin origin,
        boolean external,
        boolean generated) {

    public Symbol {
        requireText(id, "id");
        requireText(symbolKey, "symbolKey");
        requireText(projectId, "projectId");
        requireText(name, "name");
        requireText(language, "language");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(origin, "origin");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
