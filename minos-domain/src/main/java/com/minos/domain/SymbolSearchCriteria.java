package com.minos.domain;

import java.nio.charset.StandardCharsets;

/** Backend-independent structured symbol-search criteria. */
public record SymbolSearchCriteria(
        String text,
        String qualifiedName,
        SymbolKind kind,
        String moduleId,
        int limit) {

    public static final int MAX_TEXT_UTF8_BYTES = 64 * 1024;

    public SymbolSearchCriteria {
        text = bounded(blankToNull(text), "text");
        qualifiedName = bounded(blankToNull(qualifiedName), "qualifiedName");
        moduleId = bounded(blankToNull(moduleId), "moduleId");
        if (text == null && qualifiedName == null && kind == null && moduleId == null) {
            throw new IllegalArgumentException("at least one symbol search criterion is required");
        }
        if (limit < 1) throw new IllegalArgumentException("limit must be greater than zero");
    }

    public static SymbolSearchCriteria lexical(String text, int limit) {
        return new SymbolSearchCriteria(text, null, null, null, limit);
    }

    public static SymbolSearchCriteria qualifiedName(String qualifiedName, int limit) {
        return new SymbolSearchCriteria(null, qualifiedName, null, null, limit);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static String bounded(String value, String field) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_UTF8_BYTES) {
            throw new IllegalArgumentException(field + " exceeds UTF-8 byte limit: " + MAX_TEXT_UTF8_BYTES);
        }
        return value;
    }
}
