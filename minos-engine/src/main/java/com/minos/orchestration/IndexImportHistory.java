package com.minos.orchestration;

import java.time.Instant;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/**
 * The last explicit SCIP import of a project.
 *
 * @param providerVersion the provider version, {@code null} when the import did not declare one
 */
public record IndexImportHistory(String snapshotId, String providerId, String providerVersion, Instant completedAt) {

    public IndexImportHistory {
        requireText(snapshotId, "snapshotId");
        requireText(providerId, "providerId");
        if (providerVersion != null && providerVersion.isBlank()) {
            providerVersion = null;
        }
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
