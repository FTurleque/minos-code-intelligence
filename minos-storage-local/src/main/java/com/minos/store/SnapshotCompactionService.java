package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Applies snapshot retention while deriving and protecting the current active snapshot. */
public final class SnapshotCompactionService {

    private final Path storageRoot;
    private final ActiveSnapshotRepository activeSnapshots;
    private final SnapshotRetentionService retention;
    private final SnapshotIntegrityService integrity;

    public SnapshotCompactionService(Path storageRoot) throws IOException {
        SnapshotRepository repository = new SnapshotRepository(
                Objects.requireNonNull(storageRoot, "storageRoot"));
        this.storageRoot = repository.storageRoot();
        this.activeSnapshots = new ActiveSnapshotRepository(repository);
        this.retention = new SnapshotRetentionService(repository);
        this.integrity = new SnapshotIntegrityService();
    }

    public SnapshotRetentionService.RetentionResult compact(
            UUID projectId,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        return compactIfPresent(projectId, policy)
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active snapshot to protect during compaction: " + projectId));
    }

    public Optional<SnapshotRetentionService.RetentionResult> compactIfPresent(
            UUID projectId,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        return compactIfPresent(projectId, policy, Set.of());
    }

    public Optional<SnapshotRetentionService.RetentionResult> compactIfPresent(
            UUID projectId,
            SnapshotRetentionPolicy policy,
            Collection<String> additionallyProtectedSnapshotIds
    ) throws IOException {
        return compactWithActiveSnapshot(
                projectId, policy, additionallyProtectedSnapshotIds).map(CompactionResult::retention);
    }

    /** Compacts while returning the logical active id observed under the same snapshot lease. */
    public Optional<CompactionResult> compactWithActiveSnapshot(
            UUID projectId,
            SnapshotRetentionPolicy policy,
            Collection<String> additionallyProtectedSnapshotIds
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(additionallyProtectedSnapshotIds, "additionallyProtectedSnapshotIds");
        Set<String> protectedPrefixes = new HashSet<>();
        for (String snapshotId : additionallyProtectedSnapshotIds) {
            if (snapshotId == null || snapshotId.isBlank()) {
                throw new IllegalArgumentException("protected snapshot id must not be blank");
            }
            protectedPrefixes.add("snapshot-" + integrity.logicalIdHash(snapshotId) + "-");
        }
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, projectId)) {
            Optional<SnapshotDescriptor> active = activeSnapshots.read(projectId);
            if (active.isEmpty()) return Optional.empty();
            SnapshotDescriptor descriptor = active.orElseThrow();
            return Optional.of(new CompactionResult(
                    descriptor.snapshotId(),
                    retention.applyPolicyLocked(
                            projectId, descriptor.fileName(), policy, protectedPrefixes)));
        }
    }

    public record CompactionResult(
            String activeSnapshotId,
            SnapshotRetentionService.RetentionResult retention
    ) {
        public CompactionResult {
            if (activeSnapshotId == null || activeSnapshotId.isBlank()) {
                throw new IllegalArgumentException("activeSnapshotId must not be blank");
            }
            Objects.requireNonNull(retention, "retention");
        }
    }
}
