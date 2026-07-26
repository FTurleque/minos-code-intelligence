package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Applies snapshot retention while deriving and protecting the current active snapshot. */
public final class SnapshotCompactionService {

    private final ActiveSnapshotRepository activeSnapshots;
    private final SnapshotRetentionService retention;

    public SnapshotCompactionService(Path storageRoot) throws IOException {
        SnapshotRepository repository = new SnapshotRepository(
                Objects.requireNonNull(storageRoot, "storageRoot"));
        this.activeSnapshots = new ActiveSnapshotRepository(repository);
        this.retention = new SnapshotRetentionService(repository);
    }

    public SnapshotRetentionService.RetentionResult compact(
            UUID projectId,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        SnapshotDescriptor active = activeSnapshots.read(Objects.requireNonNull(projectId, "projectId"))
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active snapshot to protect during compaction: " + projectId));
        return retention.applyPolicy(projectId, active.fileName(), policy);
    }
}
