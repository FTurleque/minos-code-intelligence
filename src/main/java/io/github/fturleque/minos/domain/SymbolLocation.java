package io.github.fturleque.minos.domain;

import java.util.Objects;

/**
 * Emplacement source d'une déclaration ou occurrence.
 */
public record SymbolLocation(
        String fileId,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn) {

    public SymbolLocation {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid line range");
        }
        if (startColumn < 0 || endColumn < 0) {
            throw new IllegalArgumentException("columns must be positive or zero");
        }
        Objects.requireNonNull(fileId, "fileId");
    }
}
