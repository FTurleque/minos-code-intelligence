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
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes project-scoped index-state mutations with every other PostgreSQL project mutation. */
final class ProjectMutationIndexStateStore implements IndexStateStore {
    private static final long LEASE_POLL_MILLIS = 50L;

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

        final PostgresConnectionFactory.ScopedConnectionLease connectionLease;
        try {
            connectionLease = connections.openScopedConnection();
        } catch (SQLException exception) {
            throw new IllegalStateException("PostgreSQL lifecycle lease could not reserve a connection", exception);
        }

        boolean acquired = false;
        try {
            while (!tryAcquireLifecycleLock(connectionLease, id)) {
                try {
                    Thread.sleep(LEASE_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while waiting for PostgreSQL project lifecycle lease: " + id,
                            interrupted);
                }
            }
            acquired = true;
            HeldLifecycleLease held = new HeldLifecycleLease(id, connectionLease);
            heldLifecycleLease.set(held);
            return logicalLease(held);
        } finally {
            if (!acquired) connectionLease.close();
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
                releasePhysicalLifecycleLock(held.connectionLease, held.projectId);
            }
        };
    }

    private boolean tryAcquireLifecycleLock(
            PostgresConnectionFactory.ScopedConnectionLease connectionLease,
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

    private static void releasePhysicalLifecycleLock(
            PostgresConnectionFactory.ScopedConnectionLease connectionLease,
            UUID projectId
    ) {
        RuntimeException failure = null;
        try (PreparedStatement statement = connectionLease.connection().prepareStatement(
                "SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, PostgresProjectMutationLock.key(projectId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    connectionLease.invalidate();
                    failure = new IllegalStateException(
                            "PostgreSQL lifecycle lease was not owned while releasing project " + projectId);
                }
            }
        } catch (SQLException exception) {
            connectionLease.invalidate();
            failure = new IllegalStateException(
                    "PostgreSQL lifecycle lease release failed for project " + projectId, exception);
        } finally {
            try {
                connectionLease.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
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
        private final PostgresConnectionFactory.ScopedConnectionLease connectionLease;
        private final Thread owner = Thread.currentThread();
        private int depth = 1;

        private HeldLifecycleLease(UUID projectId, PostgresConnectionFactory.ScopedConnectionLease connectionLease) {
            this.projectId = projectId;
            this.connectionLease = connectionLease;
        }
    }
}
