package com.minos.semantic;

import java.io.IOException;

/**
 * Thrown when a semantic sync detects that the active structural snapshot changed during
 * the embedding phase, making the built index stale before it could be committed.
 * The caller should treat this as a clean abort — the durable index is unmodified.
 */
public final class StaleSemanticSyncException extends IOException {

    public StaleSemanticSyncException(String projectId, String builtFor, String currentActive) {
        super("semantic sync aborted: index built for snapshot " + builtFor
                + " but project " + projectId + " active snapshot is now " + currentActive
                + "; concurrent sync already committed the newer index");
    }
}
