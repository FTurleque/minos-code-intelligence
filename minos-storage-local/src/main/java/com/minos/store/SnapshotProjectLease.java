package com.minos.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private static final long FILE_LOCK_POLL_MILLIS = 50L;
    private static final int STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();
    private static final String LEASE_DIRECTORY = ".project-mutation-leases";

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final Thread owner;
    private boolean closed;

    private SnapshotProjectLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
        this.owner = Thread.currentThread();
    }

    static SnapshotProjectLease acquire(Path storageRoot, UUID projectId) throws IOException {
        return acquire(storageRoot, Objects.requireNonNull(projectId, "projectId").toString(), DEFAULT_ACQUIRE_TIMEOUT);
    }

    static SnapshotProjectLease acquire(Path storageRoot, String projectId) throws IOException {
        return acquire(storageRoot, projectId, DEFAULT_ACQUIRE_TIMEOUT);
    }

    static SnapshotProjectLease acquire(Path storageRoot, String projectId, Duration timeout) throws IOException {
        Duration wait = requirePositive(timeout);
        long deadline = deadline(wait);
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
        boolean jvmAcquired = false;
        FileChannel channel = null;
        try {
            jvmAcquired = tryJvmLock(jvmLock, deadline);
            if (!jvmAcquired) {
                throw new IOException("timed out waiting for project mutation JVM lease after "
                        + wait + ": " + safeProjectId);
            }
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = acquireFileLock(channel, deadline, wait, safeProjectId);
            return new SnapshotProjectLease(jvmLock, channel, fileLock);
        } catch (IOException | RuntimeException exception) {
            IOException cleanup = null;
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    cleanup = closeFailure;
                }
            }
            if (jvmAcquired) jvmLock.unlock();
            if (cleanup != null) exception.addSuppressed(cleanup);
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
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("project mutation lease must be released by its owner thread");
        }
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

    private static FileLock acquireFileLock(
            FileChannel channel,
            long deadline,
            Duration timeout,
            String projectId
    ) throws IOException {
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException unavailableInThisJvm) {
                // Treat an overlapping external channel exactly like an inter-process holder.
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("timed out waiting for cross-process project mutation lease after "
                        + timeout + ": " + projectId);
            }
            sleepUntilRetry(deadline, projectId);
        }
    }

    private static boolean tryJvmLock(ReentrantLock lock, long deadline) throws IOException {
        long remaining = Math.max(0L, deadline - System.nanoTime());
        try {
            return lock.tryLock(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for project mutation JVM lease", interrupted);
        }
    }

    private static void sleepUntilRetry(long deadline, String projectId) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) return;
        long sleepMillis = Math.max(1L, Math.min(
                FILE_LOCK_POLL_MILLIS,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for cross-process project mutation lease: "
                    + projectId, interrupted);
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Duration value = Objects.requireNonNull(timeout, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("project mutation lease timeout must be positive");
        }
        return value;
    }

    private static long deadline(Duration timeout) {
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return nanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
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
