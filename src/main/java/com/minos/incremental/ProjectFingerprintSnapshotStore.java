package com.minos.incremental;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stockage historique des empreintes associées aux snapshots d'index.
 */
public interface ProjectFingerprintSnapshotStore {

    ProjectFingerprintSnapshot publish(
            UUID projectId,
            String indexSnapshotId,
            ProjectFingerprint fingerprint
    ) throws IOException;

    void promote(UUID projectId, String indexSnapshotId) throws IOException;

    Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) throws IOException;

    Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException;

    List<String> listIndexSnapshotIds(UUID projectId) throws IOException;
}
