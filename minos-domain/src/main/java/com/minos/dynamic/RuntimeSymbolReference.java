package com.minos.dynamic;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral runtime reference, resolved without mutating the static snapshot. */
public record RuntimeSymbolReference(
        String symbolKey,
        String qualifiedName,
        String fileId,
        Integer line
) {
    private static final int MAX_ID_CHARS = 32 * 1024;

    public RuntimeSymbolReference {
        symbolKey = optionalText(symbolKey, "symbolKey", MAX_ID_CHARS);
        qualifiedName = optionalText(qualifiedName, "qualifiedName", MAX_ID_CHARS);
        fileId = normalizeFileId(fileId);
        if (line != null && line < 1) {
            throw new IllegalArgumentException("line must be greater than zero");
        }
        if (line != null && fileId == null) {
            throw new IllegalArgumentException("line requires fileId");
        }
        if (symbolKey == null && qualifiedName == null && fileId == null) {
            throw new IllegalArgumentException("runtime symbol reference requires symbolKey, qualifiedName or fileId");
        }
    }

    public String display() {
        if (symbolKey != null) return symbolKey;
        if (qualifiedName != null) return qualifiedName;
        return fileId + (line == null ? "" : ":" + line);
    }

    private static String optionalText(String value, String field, int maximum) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\t') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid or exceeds its limit");
        }
        return normalized;
    }

    private static String normalizeFileId(String value) {
        String normalized = optionalText(value, "fileId", MAX_ID_CHARS);
        if (normalized == null) return null;
        normalized = normalized.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("fileId must be project-relative");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("fileId must be a confined normalized path");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }
}
