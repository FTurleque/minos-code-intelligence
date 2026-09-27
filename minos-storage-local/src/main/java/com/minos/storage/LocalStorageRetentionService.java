package com.minos.storage;

import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexRunRetentionPolicy;
import com.minos.orchestration.IndexRunRetentionService;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.SnapshotCompactionService;
import com.minos.store.SnapshotRetentionPolicy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** File-backed retention coordinated under one inter-process per-project maintenance lock. */
final class LocalStorageRetentionService implements StorageRetentionService {
    private final Path lockRoot;
    private final SnapshotCompactionService knowledgeSnapshots;
    private final FileProjectFingerprintSnapshotStore fingerprints;
    private final FileIndexStateStore indexState;
    private final IndexRunRetentionService runs;

    LocalStorageRetentionService(
            Path home,
            Path knowledgeSnapshotRoot,
            Path indexStateRoot,
            FileProjectFingerprintSnapshotStore fingerprints,
            FileIndexStateStore indexState
    ) throws IOException {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.lockRoot = normalizedHome.resolve("retention-locks");
        Files.createDirectories(lockRoot);
        this.knowledgeSnapshots = new SnapshotCompactionService(knowledgeSnapshotRoot);
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.indexState = Objects.requireNonNull(indexState, "indexState");
        this.runs = new IndexRunRetentionService(indexStateRoot, indexState);
    }

    @Override
    public RetentionResult compact(UUID projectId, PersistentRetentionPolicy policy) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        Path lockFile = lockRoot.resolve(projectId + ".lock").normalize();
        if (!lockFile.getParent().equals(lockRoot)) {
            throw new IOException("retention lock path escapes its root");
        }
        try (FileChannel channel = FileChannel.open(
                     lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Set<String> stateProtectedSnapshotIds = new HashSet<>();
            indexState.findProjectState(projectId)
                    .flatMap(ProjectIndexState::activeSnapshotId)
                    .ifPresent(stateProtectedSnapshotIds::add);
            var knowledgeResult = knowledgeSnapshots.compactWithActiveSnapshot(
                    projectId,
                    new SnapshotRetentionPolicy(policy.maxHistoricalSnapshots()),
                    stateProtectedSnapshotIds);
            knowledgeResult.map(SnapshotCompactionService.CompactionResult::activeSnapshotId)
                    .ifPresent(stateProtectedSnapshotIds::add);
            int deletedKnowledge = knowledgeResult
                    .map(result -> result.retention().deletedHistoricalFiles().size())
                    .orElse(0);

            int deletedFingerprints = fingerprints.compact(
                    projectId, stateProtectedSnapshotIds, policy.maxHistoricalSnapshots()).deletedSnapshots();

            int deletedRuns = runs.compact(
                    projectId,
                    new IndexRunRetentionPolicy(
                            policy.maxSucceededRuns(), policy.maxNonSucceededRuns()))
                    .deletedRunIds().size();
            return new RetentionResult(deletedKnowledge, deletedFingerprints, deletedRuns);
        }
    }
}
