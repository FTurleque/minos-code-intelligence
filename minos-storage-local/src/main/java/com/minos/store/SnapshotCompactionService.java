package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Applies snapshot retention while deriving and protecting the current active snapshot. */
public final class SnapshotCompactionService {

    private final Path storageRoot;
    private final ActiveSnapshotRepository activeSnapshots;
    private final SnapshotRetentionService retention;

    public SnapshotCompactionService(Path storageRoot) throws IOException {
        SnapshotRepository repository = new SnapshotRepository(
                Objects.requireNonNull(storageRoot, "storageRoot"));
        this.storageRoot = repository.storageRoot();
        this.activeSnapshots = new ActiveSnapshotRepository(repository);
        this.retention = new SnapshotRetentionService(repository);
    }

    public SnapshotRetentionService.RetentionResult compact(
            UUID projectId,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, projectId)) {
            SnapshotDescriptor active = activeSnapshots.read(projectId)
                    .orElseThrow(() -> new IllegalStateException(
                            "project has no active snapshot to protect during compaction: " + projectId));
            return retention.applyPolicyLocked(projectId, active.fileName(), policy);
        }
    }
}
