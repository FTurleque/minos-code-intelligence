package com.minos.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final int STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();
    private static final String LEASE_DIRECTORY = ".project-mutation-leases";

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private boolean closed;

    private SnapshotProjectLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static SnapshotProjectLease acquire(Path storageRoot, UUID projectId) throws IOException {
        return acquire(storageRoot, Objects.requireNonNull(projectId, "projectId").toString());
    }

    static SnapshotProjectLease acquire(Path storageRoot, String projectId) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        Path commonRoot = normalizedRoot.getParent() != null ? normalizedRoot.getParent() : normalizedRoot;
        Path directory = commonRoot.resolve(LEASE_DIRECTORY).toAbsolutePath().normalize();
        if (!directory.startsWith(commonRoot)) {
            throw new IOException("project mutation lease directory escapes storage family root");
        }
        Files.createDirectories(directory);
        String safeProjectId = requireSafeProjectId(projectId);
        Path file = directory.resolve(safeProjectId + ".lock").toAbsolutePath().normalize();
        if (!file.startsWith(directory)) {
            throw new IOException("project mutation lease file escapes lease directory");
        }
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(file.hashCode(), JVM_LOCKS.length)];
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return new SnapshotProjectLease(jvmLock, channel, channel.lock());
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            jvmLock.unlock();
            throw exception;
        }
    }

    static Path lockFile(Path storageRoot, String projectId) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        Path commonRoot = normalizedRoot.getParent() != null ? normalizedRoot.getParent() : normalizedRoot;
        return commonRoot.resolve(LEASE_DIRECTORY).resolve(requireSafeProjectId(projectId) + ".lock")
                .toAbsolutePath().normalize();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { fileLock.release(); } catch (IOException exception) { failure = exception; }
        try { channel.close(); } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        } finally {
            jvmLock.unlock();
        }
        if (failure != null) throw failure;
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
