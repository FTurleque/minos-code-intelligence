package com.minos.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port de stockage des états de run et de projet.
 */
public interface IndexStateStore {

    Optional<ProjectIndexState> findProjectState(UUID projectId);

    Optional<IndexingRun> findRun(UUID runId);

    List<IndexingRun> listRuns(UUID projectId);

    void saveProjectState(ProjectIndexState state);

    void saveRun(IndexingRun run);
}
