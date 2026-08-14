package com.minos.orchestration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port de stockage des états de run et de projet.
 *
 * <p>The store also owns the project-scoped lifecycle lease. This places the
 * single-writer invariant next to the durable state it protects instead of in
 * transport adapters. Implementations that cannot provide a qualified lease
 * fail closed.</p>
 */
public interface IndexStateStore {

    Optional<ProjectIndexState> findProjectState(UUID projectId);

    Optional<IndexingRun> findRun(UUID runId);

    List<IndexingRun> listRuns(UUID projectId);

    void saveProjectState(ProjectIndexState state);

    void saveRun(IndexingRun run);

    /**
     * Acquires exclusive ownership of one project's complete indexing lifecycle.
     * The lease must remain held from the authoritative-state check through
     * provider execution, snapshot promotion and metadata finalization.
     */
    default ProjectLease acquireProjectLease(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        throw new IllegalStateException(
                "index state store does not provide a qualified project lifecycle lease");
    }

    @FunctionalInterface
    interface ProjectLease extends AutoCloseable {
        @Override
        void close();
    }
}
