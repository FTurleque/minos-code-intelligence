package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : persistance de resumableRunId et compatibilité des états projet existants. */
class FileIndexStateStoreResumableStateTest {

    private static final Instant UPDATED = Instant.parse("2026-09-26T11:00:00Z");

    @TempDir
    Path root;

    @Test
    void persistsTheResumableRunAcrossReopen() throws Exception {
        Path stateRoot = root.resolve("state");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ProjectIndexState state = new ProjectIndexState(projectId, ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-old"), Optional.of(runId), UPDATED, Optional.of("interrupted"),
                Optional.of(runId));

        new FileIndexStateStore(stateRoot).saveProjectState(state);
        ProjectIndexState reloaded = new FileIndexStateStore(stateRoot).findProjectState(projectId).orElseThrow();

        assertEquals(state, reloaded);
        assertTrue(Files.readString(stateRoot.resolve("projects").resolve(projectId + ".properties"))
                .contains("resumableRunId=" + runId));
    }

    @Test
    void projectStateWrittenByThePreviousVersionHasNoResumableRun() throws Exception {
        Path stateRoot = root.resolve("legacy");
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        Files.writeString(stateRoot.resolve("projects").resolve(projectId + ".properties"), """
                projectId=%s
                availability=STALE
                activeSnapshotId=snapshot-old
                latestRunId=%s
                updatedAt=2026-09-01T10:00:00Z
                detail=latest refresh failed
                """.formatted(projectId, UUID.randomUUID()));

        ProjectIndexState state = store.findProjectState(projectId).orElseThrow();

        assertEquals(ProjectIndexState.Availability.STALE, state.availability());
        assertEquals(Optional.empty(), state.resumableRunId());
    }

    @Test
    void invalidResumableRunIdIsDroppedRatherThanFailingTheRead() throws Exception {
        Path stateRoot = root.resolve("invalid");
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        Files.writeString(stateRoot.resolve("projects").resolve(projectId + ".properties"), """
                projectId=%s
                availability=FAILED
                activeSnapshotId=
                latestRunId=
                updatedAt=2026-09-01T10:00:00Z
                detail=interrupted
                resumableRunId=not-a-uuid
                """.formatted(projectId));

        assertEquals(Optional.empty(), store.findProjectState(projectId).orElseThrow().resumableRunId());
    }
}
