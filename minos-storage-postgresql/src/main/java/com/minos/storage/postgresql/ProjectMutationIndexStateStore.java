package com.minos.storage.postgresql;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Serializes project-scoped index-state mutations with every other PostgreSQL project mutation. */
final class ProjectMutationIndexStateStore implements IndexStateStore {
    private final PostgresConnectionFactory connections;
    private final IndexStateStore delegate;

    ProjectMutationIndexStateStore(PostgresConnectionFactory connections, IndexStateStore delegate) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
}
