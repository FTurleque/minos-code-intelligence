package com.minos.orchestration;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-JVM exclusive lease for one project's indexing lifecycle. */
public final class ProjectIndexLease implements AutoCloseable {

    static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    private static final long FILE_LOCK_POLL_MILLIS = 50L;
    private static final Object JVM_LOCK_MONITOR = new Object();
    private static final ConcurrentMap<Path, LockState> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path lockPath;
    private final LockState lockState;
    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final Thread owner;
    private boolean closed;

    private ProjectIndexLease(
            Path lockPath, LockState lockState, FileChannel channel, FileLock fileLock
    ) {
        this.lockPath = lockPath;
        this.lockState = lockState;
        this.jvmLock = lockState.lock;
        this.channel = channel;
        this.fileLock = fileLock;
        this.owner = Thread.currentThread();
    }

    public static ProjectIndexLease acquire(Path minosHome, UUID projectId) throws IOException {
        return acquire(minosHome, projectId, DEFAULT_ACQUIRE_TIMEOUT);
    }

    static ProjectIndexLease acquire(Path minosHome, UUID projectId, Duration timeout) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        Objects.requireNonNull(projectId, "projectId");
        Duration wait = requirePositive(timeout);
        long deadline = deadline(wait);
        Path directory = home.resolve("locks").resolve("indexing");
        Files.createDirectories(directory);
        Path lockPath = directory.resolve(projectId + ".lock").toAbsolutePath().normalize();
        if (!lockPath.startsWith(directory.toAbsolutePath().normalize())) {
            throw new IOException("project indexing lock escapes MINOS lock directory");
        }
        LockState state;
        synchronized (JVM_LOCK_MONITOR) {
            state = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new LockState());
            state.references++;
        }
        boolean jvmAcquired = false;
        FileChannel channel = null;
        try {
            jvmAcquired = tryJvmLock(state.lock, deadline);
            if (!jvmAcquired) {
                throw new IOException("timed out waiting for project indexing JVM lease after " + wait
                        + ": " + projectId);
            }
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = acquireFileLock(channel, deadline, wait, projectId);
            return new ProjectIndexLease(lockPath, state, channel, lock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            if (jvmAcquired) state.lock.unlock();
            releaseState(lockPath, state);
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("project indexing lease must be released by its owner thread");
        }
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            fileLock.release();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        } finally {
            jvmLock.unlock();
            releaseState(lockPath, lockState);
        }
        if (failure != null) throw failure;
    }

    static int retainedJvmLockCount() {
        synchronized (JVM_LOCK_MONITOR) {
            return JVM_LOCKS.size();
        }
    }

    private static FileLock acquireFileLock(
            FileChannel channel,
            long deadline,
            Duration timeout,
            UUID projectId
    ) throws IOException {
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException unavailableInThisJvm) {
                // Treat an overlapping external channel exactly like an inter-process holder.
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("timed out waiting for cross-process project indexing lease after "
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
            throw new IOException("interrupted while waiting for project indexing JVM lease", interrupted);
        }
    }

    private static void sleepUntilRetry(long deadline, UUID projectId) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) return;
        long sleepMillis = Math.max(1L, Math.min(
                FILE_LOCK_POLL_MILLIS,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for cross-process project indexing lease: "
                    + projectId, interrupted);
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Duration value = Objects.requireNonNull(timeout, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("project indexing lease timeout must be positive");
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

    private static void releaseState(Path path, LockState state) {
        synchronized (JVM_LOCK_MONITOR) {
            state.references--;
            if (state.references < 0) throw new IllegalStateException("project index JVM lock reference underflow");
            if (state.references == 0) JVM_LOCKS.remove(path, state);
        }
    }

    private static final class LockState {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
