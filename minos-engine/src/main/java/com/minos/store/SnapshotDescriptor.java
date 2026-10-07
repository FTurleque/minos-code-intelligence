package com.minos.store;

import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

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
        requireText(Objects.requireNonNull(snapshotId, "snapshotId"), "snapshotId");
        requireText(Objects.requireNonNull(fileName, "fileName"), "fileName");
        requireText(Objects.requireNonNull(sha256, "sha256"), "sha256");
        if (symbolCount < 0 || occurrenceCount < 0 || relationshipCount < 0) {
            throw new IllegalArgumentException("snapshot counts must not be negative");
        }
    }
}
