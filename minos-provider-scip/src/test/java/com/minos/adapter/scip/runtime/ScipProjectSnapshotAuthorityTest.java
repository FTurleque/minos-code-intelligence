package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipProjectSnapshotAuthorityTest {

    @Test
    void emptyProductionStoreIsExplicitlyObservedAsNoActiveSnapshot(@TempDir Path root) throws Exception {
        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(root.resolve("home"));

        var observation = lifecycle.observeActiveSnapshot(UUID.randomUUID());

        assertEquals(Status.NO_ACTIVE_SNAPSHOT, observation.status());
        assertTrue(observation.snapshotId().isEmpty());
    }
}
