package com.minos.storage.postgresql;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes project-scoped index-state mutations with every other PostgreSQL project mutation. */
final class ProjectMutationIndexStateStore implements IndexStateStore {
    private static final long LEASE_POLL_MILLIS = 50L;
    private final PostgresConnectionFactory connections;
    private final IndexStateStore delegate;
    private final ThreadLocal<HeldLifecycleLease> heldLifecycleLease = new ThreadLocal<>();
    private final Object localGateMonitor = new Object();
    private final Map<UUID, LocalGateState> localGates = new HashMap<>();

    ProjectMutationIndexStateStore(PostgresConnectionFactory connections, IndexStateStore delegate) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ProjectLease acquireProjectLease(UUID projectId) {
        UUID id = Objects.requireNonNull(projectId, "projectId");
        HeldLifecycleLease nested = heldLifecycleLease.get();
        if (nested != null) {
            if (!nested.projectId.equals(id)) {
                throw new IllegalStateException(
                        "cannot acquire a second PostgreSQL project lifecycle lease on the same thread: held="
                                + nested.projectId + " requested=" + id);
            }
            nested.depth++;
            return logicalLease(nested);
        }

        LocalGateLease gateLease = null;
        PostgresConnectionFactory.DedicatedConnectionLease connectionLease = null;
        boolean sessionLockAcquired = false;
        try {
            gateLease = acquireLocalGate(id);
            connectionLease = connections.openDedicatedConnection();
            long deadline = System.nanoTime() + connections.acquireTimeout().toNanos();
            while (!tryAcquireLifecycleLock(connectionLease, id)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "timed out waiting for PostgreSQL project lifecycle lease after "
                                    + connections.acquireTimeout() + ": " + id);
                }
                sleepForLease(id);
            }
            sessionLockAcquired = true;
            PostgresProjectMutationLock.enterLifecycle(id);
            HeldLifecycleLease held = new HeldLifecycleLease(id, connectionLease, gateLease);
            heldLifecycleLease.set(held);
            return logicalLease(held);
        } catch (SQLException exception) {
            throw new IllegalStateException("PostgreSQL lifecycle lease could not reserve a dedicated connection", exception);
        } finally {
            if (heldLifecycleLease.get() == null) {
                if (sessionLockAcquired && connectionLease != null) {
                    releaseSessionLockBestEffort(connectionLease, id);
                }
                if (connectionLease != null) connectionLease.close();
                if (gateLease != null) gateLease.close();
            }
        }
    }

    @Override
    public Optional<ProjectIndexState> findProjectState(UUID projectId) {
        return delegate.findProjectState(projectId);
    }

    @Override
    public Optional<IndexingRun> findRun(UUID runId) {
        return delegate.findRun(runId);
    }

    @Override
    public List<IndexingRun> listRuns(UUID projectId) {
        return delegate.listRuns(projectId);
    }

    @Override
    public void saveProjectState(ProjectIndexState state) {
        Objects.requireNonNull(state, "state");
        mutate(state.projectId(), () -> delegate.saveProjectState(state), "save project state");
    }

    @Override
    public void saveRun(IndexingRun run) {
        Objects.requireNonNull(run, "run");
        mutate(run.projectId(), () -> delegate.saveRun(run), "save indexing run");
    }

    private ProjectLease logicalLease(HeldLifecycleLease held) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (Thread.currentThread() != held.owner) {
                throw new IllegalStateException("PostgreSQL lifecycle lease must be released by its owner thread");
            }
            if (heldLifecycleLease.get() != held) {
                throw new IllegalStateException("PostgreSQL lifecycle lease lost thread ownership context");
            }
            if (!closed.compareAndSet(false, true)) return;
            held.depth--;
            if (held.depth < 0) {
                throw new IllegalStateException("PostgreSQL lifecycle lease depth underflow");
            }
            if (held.depth == 0) {
                heldLifecycleLease.remove();
                releasePhysicalLifecycleLock(held);
            }
        };
    }

    private LocalGateLease acquireLocalGate(UUID projectId) {
        LocalGateState state;
        synchronized (localGateMonitor) {
            state = localGates.computeIfAbsent(projectId, ignored -> new LocalGateState());
            state.references++;
        }
        boolean acquired = false;
        try {
            acquired = state.lock.tryLock(connections.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                        "timed out waiting for local PostgreSQL lifecycle gate after "
                                + connections.acquireTimeout() + ": " + projectId);
            }
            return new LocalGateLease(projectId, state);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for local PostgreSQL lifecycle gate: " + projectId,
                    interrupted);
        } finally {
            if (!acquired) releaseLocalGateReference(projectId, state);
        }
    }

    private void releaseLocalGateReference(UUID projectId, LocalGateState state) {
        synchronized (localGateMonitor) {
            state.references--;
            if (state.references < 0) throw new IllegalStateException("PostgreSQL local lifecycle gate underflow");
            if (state.references == 0) localGates.remove(projectId, state);
        }
    }

    private static void sleepForLease(UUID projectId) {
        try {
            Thread.sleep(LEASE_POLL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for PostgreSQL project lifecycle lease: " + projectId,
                    interrupted);
        }
    }

    private boolean tryAcquireLifecycleLock(
            PostgresConnectionFactory.DedicatedConnectionLease connectionLease,
            UUID projectId
    ) {
        try (PreparedStatement statement = connectionLease.connection().prepareStatement(
                "SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, PostgresProjectMutationLock.key(projectId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("PostgreSQL lifecycle lock query returned no row");
                return result.getBoolean(1);
            }
        } catch (SQLException exception) {
            connectionLease.invalidate();
            throw new IllegalStateException("PostgreSQL lifecycle lease acquisition failed for project " + projectId,
                    exception);
        }
    }

    private static void releasePhysicalLifecycleLock(HeldLifecycleLease held) {
        RuntimeException failure = null;
        try {
            releaseSessionLock(held.connectionLease, held.projectId);
        } catch (RuntimeException unlockFailure) {
            failure = unlockFailure;
        }
        try {
            PostgresProjectMutationLock.exitLifecycle(held.projectId);
        } catch (RuntimeException contextFailure) {
            if (failure == null) failure = contextFailure; else failure.addSuppressed(contextFailure);
        }
        try {
            held.connectionLease.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
        } finally {
            held.gateLease.close();
        }
        if (failure != null) throw failure;
    }

    private static void releaseSessionLock(
            PostgresConnectionFactory.DedicatedConnectionLease connectionLease,
            UUID projectId
    ) {
        try (PreparedStatement statement = connectionLease.connection().prepareStatement(
                "SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, PostgresProjectMutationLock.key(projectId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    connectionLease.invalidate();
                    throw new IllegalStateException(
                            "PostgreSQL lifecycle lease was not owned while releasing project " + projectId);
                }
            }
        } catch (SQLException exception) {
            connectionLease.invalidate();
            throw new IllegalStateException(
                    "PostgreSQL lifecycle lease release failed for project " + projectId, exception);
        }
    }

    private static void releaseSessionLockBestEffort(
            PostgresConnectionFactory.DedicatedConnectionLease connectionLease,
            UUID projectId
    ) {
        try {
            releaseSessionLock(connectionLease, projectId);
        } catch (RuntimeException ignored) {
            connectionLease.invalidate();
        }
    }

    private void mutate(UUID projectId, Runnable mutation, String action) {
        try {
            connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                mutation.run();
                return null;
            });
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("PostgreSQL index state mutation failed to " + action, exception);
        }
    }

    private static final class HeldLifecycleLease {
        private final UUID projectId;
        private final PostgresConnectionFactory.DedicatedConnectionLease connectionLease;
        private final LocalGateLease gateLease;
        private final Thread owner = Thread.currentThread();
        private int depth = 1;

        private HeldLifecycleLease(
                UUID projectId,
                PostgresConnectionFactory.DedicatedConnectionLease connectionLease,
                LocalGateLease gateLease
        ) {
            this.projectId = projectId;
            this.connectionLease = connectionLease;
            this.gateLease = gateLease;
        }
    }

    private static final class LocalGateState {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }

    private final class LocalGateLease implements AutoCloseable {
        private final UUID projectId;
        private final LocalGateState state;
        private final Thread owner = Thread.currentThread();
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalGateLease(UUID projectId, LocalGateState state) {
            this.projectId = projectId;
            this.state = state;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("PostgreSQL local lifecycle gate must be released by its owner thread");
            }
            if (!closed.compareAndSet(false, true)) return;
            state.lock.unlock();
            releaseLocalGateReference(projectId, state);
        }
    }
}
