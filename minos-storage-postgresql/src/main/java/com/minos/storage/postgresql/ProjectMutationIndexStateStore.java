package com.minos.storage.postgresql;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes project-scoped index-state mutations with every other PostgreSQL project mutation. */
final class ProjectMutationIndexStateStore implements IndexStateStore {
    private static final long LEASE_POLL_MILLIS = 50L;
    private static final int LOCAL_GATE_STRIPES = 256;
    private static final ReentrantLock[] LOCAL_GATES = gates();

    private final PostgresConnectionFactory connections;
    private final IndexStateStore delegate;
    private final ThreadLocal<HeldLifecycleLease> heldLifecycleLease = new ThreadLocal<>();

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

        ReentrantLock gate = gate(id);
        boolean gateAcquired = false;
        PostgresConnectionFactory.DedicatedConnectionLease connectionLease = null;
        boolean sessionLockAcquired = false;
        try {
            gateAcquired = tryAcquireGate(gate, id);
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
            HeldLifecycleLease held = new HeldLifecycleLease(id, connectionLease, gate);
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
                if (gateAcquired) gate.unlock();
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
            if (!closed.compareAndSet(false, true)) return;
            if (Thread.currentThread() != held.owner) {
                throw new IllegalStateException("PostgreSQL lifecycle lease must be released by its owner thread");
            }
            if (heldLifecycleLease.get() != held) {
                throw new IllegalStateException("PostgreSQL lifecycle lease lost thread ownership context");
            }
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

    private boolean tryAcquireGate(ReentrantLock gate, UUID projectId) {
        try {
            if (gate.tryLock(connections.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) return true;
            throw new IllegalStateException(
                    "timed out waiting for local PostgreSQL lifecycle gate after "
                            + connections.acquireTimeout() + ": " + projectId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for local PostgreSQL lifecycle gate: " + projectId,
                    interrupted);
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
            held.gate.unlock();
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

    private static ReentrantLock gate(UUID projectId) {
        return LOCAL_GATES[Math.floorMod(projectId.hashCode(), LOCAL_GATES.length)];
    }

    private static ReentrantLock[] gates() {
        ReentrantLock[] values = new ReentrantLock[LOCAL_GATE_STRIPES];
        for (int index = 0; index < values.length; index++) values[index] = new ReentrantLock(true);
        return values;
    }

    private static final class HeldLifecycleLease {
        private final UUID projectId;
        private final PostgresConnectionFactory.DedicatedConnectionLease connectionLease;
        private final ReentrantLock gate;
        private final Thread owner = Thread.currentThread();
        private int depth = 1;

        private HeldLifecycleLease(
                UUID projectId,
                PostgresConnectionFactory.DedicatedConnectionLease connectionLease,
                ReentrantLock gate
        ) {
            this.projectId = projectId;
            this.connectionLease = connectionLease;
            this.gate = gate;
        }
    }
}
