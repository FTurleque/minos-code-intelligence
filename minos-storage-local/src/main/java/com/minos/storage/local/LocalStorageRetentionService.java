package com.minos.storage.local;

import com.minos.io.BoundedFileLease;
import com.minos.io.PrivateLocalStorage;
import com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.orchestration.IndexRunRetentionPolicy;
import com.minos.storage.local.orchestration.IndexRunRetentionService;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.PersistentRetentionPolicy;
import com.minos.storage.StorageRetentionService;
import com.minos.storage.local.store.SnapshotCompactionService;
import com.minos.storage.local.store.SnapshotRetentionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed retention coordinated under one inter-process per-project maintenance lock.
 *
 * <p>Lock order (FIAB-SUIVI section 8.6): the caller holds the project lifecycle lease, which is how
 * retention and the lifecycle exclude each other; the retention lock is taken after it, and each
 * store compaction then takes and releases the project mutation lease in sequence. Retention does not
 * take the lifecycle lease itself: Q4 (a prepared snapshot deleted before its promotion) is not
 * reproducible under the production policy, see {@code LocalStorageRetentionPreparedSnapshotTest}.</p>
 */
final class LocalStorageRetentionService implements StorageRetentionService {
    /** The bound every other lock of the storage layer uses (project mutation lease, registry, vectors). */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final int LOCK_STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final Path lockRoot;
    private final Duration lockTimeout;
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
        this(home, knowledgeSnapshotRoot, indexStateRoot, fingerprints, indexState, LOCK_TIMEOUT);
    }

    LocalStorageRetentionService(
            Path home,
            Path knowledgeSnapshotRoot,
            Path indexStateRoot,
            FileProjectFingerprintSnapshotStore fingerprints,
            FileIndexStateStore indexState,
            Duration lockTimeout
    ) throws IOException {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.lockRoot = normalizedHome.resolve("retention-locks");
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        PrivateLocalStorage.ensurePrivateDirectory(lockRoot);
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
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(lockFile.hashCode(), JVM_LOCKS.length)];
        try (BoundedFileLease ignored = BoundedFileLease.acquire(
                lockFile, jvmLock, lockTimeout, "storage retention lock")) {
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

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }
}
