package com.minos.incremental;

import com.minos.orchestration.ProjectIndexState;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Vérifie qu'un éventuel snapshot d'empreintes actif correspond exactement au
 * snapshot d'index actif annoncé par le lifecycle M1.
 */
public final class ProjectFingerprintSnapshotAlignmentService {

    private final ProjectFingerprintSnapshotStore store;

    public ProjectFingerprintSnapshotAlignmentService(ProjectFingerprintSnapshotStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Optional<ProjectFingerprintSnapshot> loadAlignedWithActiveIndex(ProjectIndexState indexState)
            throws IOException {
        Objects.requireNonNull(indexState, "indexState");
        Optional<ProjectFingerprintSnapshot> activeFingerprint = store.loadActive(indexState.projectId());
        Optional<String> activeIndexId = indexState.activeSnapshotId();

        if (activeIndexId.isEmpty()) {
            if (activeFingerprint.isPresent()) {
                throw new IOException("active fingerprint snapshot exists without an active index snapshot");
            }
            return Optional.empty();
        }
        if (activeFingerprint.isEmpty()) {
            return Optional.empty();
        }
        if (!activeFingerprint.orElseThrow().indexSnapshotId().equals(activeIndexId.orElseThrow())) {
            throw new IOException("active fingerprint snapshot is not aligned with the active index snapshot");
        }
        return activeFingerprint;
    }
}
