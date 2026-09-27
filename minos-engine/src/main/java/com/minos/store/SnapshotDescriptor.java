package com.minos.store;

import java.util.Objects;

/** Metadata persisted by the active snapshot pointer. */
public record SnapshotDescriptor(
        int formatVersion,
        String snapshotId,
        String fileName,
        String sha256,
        int symbolCount,
        int occurrenceCount,
        int relationshipCount
) {
    public SnapshotDescriptor {
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        requireText(snapshotId, "snapshotId");
        requireText(fileName, "fileName");
        requireText(sha256, "sha256");
        if (symbolCount < 0 || occurrenceCount < 0 || relationshipCount < 0) {
            throw new IllegalArgumentException("snapshot counts must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
