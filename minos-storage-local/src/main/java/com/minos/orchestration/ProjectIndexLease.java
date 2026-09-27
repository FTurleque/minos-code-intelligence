package com.minos.orchestration;

import com.minos.io.BoundedFileLease;
import com.minos.io.DurableAtomicFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-JVM exclusive lease for one project's indexing lifecycle. */
public final class ProjectIndexLease implements AutoCloseable {

    static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    private static final Object JVM_LOCK_MONITOR = new Object();
    private static final ConcurrentMap<Path, LockState> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path lockPath;
    private final LockState lockState;
    private final BoundedFileLease lease;
    private final Thread owner;
    private boolean closed;

    private ProjectIndexLease(Path lockPath, LockState lockState, BoundedFileLease lease) {
        this.lockPath = lockPath;
        this.lockState = lockState;
        this.lease = lease;
        this.owner = Thread.currentThread();
    }

    public static ProjectIndexLease acquire(Path minosHome, UUID projectId) throws IOException {
        return acquire(minosHome, projectId, DEFAULT_ACQUIRE_TIMEOUT);
    }

    static ProjectIndexLease acquire(Path minosHome, UUID projectId, Duration timeout) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        UUID id = Objects.requireNonNull(projectId, "projectId");
        DurableAtomicFile.ensureDirectory(home, "project indexing storage root");
        Path locks = home.resolve("locks").toAbsolutePath().normalize();
        DurableAtomicFile.ensureDirectory(locks, "project indexing lock root");
        Path directory = locks.resolve("indexing").toAbsolutePath().normalize();
        DurableAtomicFile.ensureDirectory(directory, "project indexing lock directory");
        Path lockPath = directory.resolve(id + ".lock").toAbsolutePath().normalize();
        if (!lockPath.startsWith(directory)) {
            throw new IOException("project indexing lock escapes MINOS lock directory");
        }

        LockState state = retainState(lockPath);
        try {
            BoundedFileLease lease = BoundedFileLease.acquire(
                    lockPath,
                    state.lock,
                    timeout,
                    "project indexing lease: " + id);
            return new ProjectIndexLease(lockPath, state, lease);
        } catch (IOException | RuntimeException failure) {
            releaseState(lockPath, state);
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("project indexing lease must be released by its owner thread");
        }
        if (closed) return;
        closed = true;
        try {
            lease.close();
        } finally {
            releaseState(lockPath, lockState);
        }
    }

    static int retainedJvmLockCount() {
        synchronized (JVM_LOCK_MONITOR) {
            return JVM_LOCKS.size();
        }
    }

    private static LockState retainState(Path lockPath) {
        synchronized (JVM_LOCK_MONITOR) {
            LockState state = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new LockState());
            state.references++;
            return state;
        }
    }

    private static void releaseState(Path path, LockState state) {
        synchronized (JVM_LOCK_MONITOR) {
            state.references--;
            if (state.references < 0) {
                throw new IllegalStateException("project index JVM lock reference underflow");
            }
            if (state.references == 0) JVM_LOCKS.remove(path, state);
        }
    }

    private static final class LockState {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
