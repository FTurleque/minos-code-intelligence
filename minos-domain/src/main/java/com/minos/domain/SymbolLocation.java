package com.minos.domain;

import java.util.Objects;

/**
 * Emplacement source d'une déclaration ou occurrence.
 *
 * <p>Les lignes sont normalisées en base 1. Les colonnes restent des offsets en base 0
 * exprimés dans l'unité indiquée par {@link PositionEncoding}.</p>
 */
public record SymbolLocation(
        String fileId,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        PositionEncoding positionEncoding) {

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
        if (startLine == endLine && endColumn < startColumn) {
            throw new IllegalArgumentException("end column must not precede start column on the same line");
        }
        Objects.requireNonNull(positionEncoding, "positionEncoding");
    }
}
