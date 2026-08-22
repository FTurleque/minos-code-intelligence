package com.minos.store;

import com.minos.io.BoundedFileLease;
import com.minos.io.DurableAtomicFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-JVM mutation lease shared by structural snapshot publication/promotion, semantic commit and
 * structural retention for one project.
 *
 * <p>Storage implementations live in sibling directories below the MINOS home. The lock therefore
 * lives at their common parent rather than inside one particular store; otherwise a semantic recheck
 * and a structural promotion can both hold different "project" locks and race.</p>
 */
final class SnapshotProjectLease implements AutoCloseable {
    static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    private static final int STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();
    private static final String LEASE_DIRECTORY = ".project-mutation-leases";

    private final BoundedFileLease lease;

    private SnapshotProjectLease(BoundedFileLease lease) {
        this.lease = lease;
    }

    static SnapshotProjectLease acquire(Path storageRoot, UUID projectId) throws IOException {
        return acquire(storageRoot, Objects.requireNonNull(projectId, "projectId").toString(), DEFAULT_ACQUIRE_TIMEOUT);
    }

    static SnapshotProjectLease acquire(Path storageRoot, String projectId) throws IOException {
        return acquire(storageRoot, projectId, DEFAULT_ACQUIRE_TIMEOUT);
    }

    static SnapshotProjectLease acquire(Path storageRoot, String projectId, Duration timeout) throws IOException {
        Path file = lockFile(storageRoot, projectId);
        DurableAtomicFile.ensureDirectory(file.getParent(), "project mutation lease directory");
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(file.hashCode(), JVM_LOCKS.length)];
        return new SnapshotProjectLease(BoundedFileLease.acquire(
                file,
                jvmLock,
                timeout,
                "project mutation lease: " + requireSafeProjectId(projectId)));
    }

    static Path lockFile(Path storageRoot, String projectId) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        Path commonRoot = normalizedRoot.getParent() != null ? normalizedRoot.getParent() : normalizedRoot;
        Path directory = commonRoot.resolve(LEASE_DIRECTORY).toAbsolutePath().normalize();
        if (!directory.startsWith(commonRoot)) {
            throw new IOException("project mutation lease directory escapes storage family root");
        }
        String safeProjectId = requireSafeProjectId(projectId);
        Path file = directory.resolve(safeProjectId + ".lock").toAbsolutePath().normalize();
        if (!file.startsWith(directory)) {
            throw new IOException("project mutation lease file escapes lease directory");
        }
        return file;
    }

    @Override
    public void close() throws IOException {
        lease.close();
    }

    private static String requireSafeProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (!projectId.matches("[A-Za-z0-9._-]{1,200}")) {
            throw new IllegalArgumentException("projectId contains unsafe lease path characters");
        }
        return projectId;
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }
}
