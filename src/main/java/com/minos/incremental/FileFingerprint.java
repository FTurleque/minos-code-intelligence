package com.minos.incremental;

import java.util.Locale;
import java.util.Objects;

/**
 * Empreinte factuelle d'un fichier visible du workspace.
 */
public record FileFingerprint(
        String relativePath,
        long sizeBytes,
        String sha256
) {
    public FileFingerprint {
        relativePath = requirePortableRelativePath(relativePath);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        sha256 = requireSha256(sha256);
    }

    private static String requirePortableRelativePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (value.startsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException("relativePath must be portable and relative");
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("relativePath must be normalized: " + value);
            }
        }
        return value;
    }

    static String requireSha256(String value) {
        Objects.requireNonNull(value, "sha256");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
        }
        return normalized;
    }
}
