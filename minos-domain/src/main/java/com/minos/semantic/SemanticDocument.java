package com.minos.semantic;

import java.util.Objects;

/** Provider-independent, reconstructible semantic indexing unit. */
public record SemanticDocument(
        String id,
        String stableKey,
        String projectId,
        String snapshotId,
        SemanticDocumentKind kind,
        String sourceId,
        String fileId,
        int startLine,
        int endLine,
        String content,
        String checksum
) {
    public SemanticDocument {
        requireText(id, "id");
        requireText(stableKey, "stableKey");
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(kind, "kind");
        requireText(sourceId, "sourceId");
        if (fileId != null && fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must be null or non-blank");
        }
        if (startLine < 0 || endLine < startLine) {
            throw new IllegalArgumentException("invalid semantic document line range");
        }
        content = Objects.requireNonNull(content, "content");
        requireText(checksum, "checksum");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
