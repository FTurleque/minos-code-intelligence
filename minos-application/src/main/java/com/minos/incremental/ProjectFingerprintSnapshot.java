package com.minos.incremental;

import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot d'empreintes explicitement associé à un snapshot d'index MINOS.
 *
 * <p>L'association est factuelle : ce contrat n'affirme pas qu'un snapshot
 * d'empreintes peut être utilisé pour une indexation partielle.</p>
 */
public record ProjectFingerprintSnapshot(
        UUID projectId,
        String indexSnapshotId,
        ProjectFingerprint fingerprint
) {
    public ProjectFingerprintSnapshot {
        Objects.requireNonNull(projectId, "projectId");
        indexSnapshotId = requireText(indexSnapshotId, "indexSnapshotId");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
