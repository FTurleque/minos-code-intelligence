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
     *
     * <p>This is the first lock of the project lock order (FIAB-SUIVI section 8.6, ADR 0039 (l)):
     * the lifecycle lease, then the snapshot mutation lease, then the semantic sync lock, then the
     * in-memory monitors. It is never acquired while a later lock of the order is held, and it is
     * never acquired by a read: status reads take no lease and write nothing.</p>
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
