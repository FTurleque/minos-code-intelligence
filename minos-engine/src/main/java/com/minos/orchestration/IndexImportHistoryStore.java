package com.minos.orchestration;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * The import history of a project: the snapshot, provider and completion time of its last explicit SCIP import.
 * It is secondary evidence beside the active snapshot; the composition root supplies the implementation, which
 * alone owns the persisted format.
 */
public interface IndexImportHistoryStore {

    /**
     * The history of a project, empty when none was written.
     *
     * @throws IOException when a history exists but cannot be read; the message names the file leaf, never an
     *         absolute path
     */
    Optional<IndexImportHistory> read(UUID projectId) throws IOException;

    /** Replaces the history of a project atomically. */
    void write(UUID projectId, IndexImportHistory history) throws IOException;
}
