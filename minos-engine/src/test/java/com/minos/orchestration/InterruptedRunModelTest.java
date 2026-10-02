package com.minos.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : état INTERRUPTED et resumableRunId (ADR 0039 §2). */
class InterruptedRunModelTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T10:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-09-26T11:00:00Z");

    @Test
    void interruptedRunIsTerminalWithItsPhasePreservedAndAuthorityUnchanged() {
        IndexingRun run = new IndexingRun(
                UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.of(OBSERVED), List.of(),
                Optional.empty(), Optional.of("snapshot-old"), Optional.of("snapshot-old"),
                Optional.of("interrupted"), IndexingRun.CURRENT_FORMAT_VERSION);

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status());
        assertEquals(IndexingRun.Phase.PROVIDER_EXECUTION, run.phase());
        assertTrue(run.completedAt().isPresent(), "an interrupted run is terminal for availability");
    }

    @Test
    void interruptedRunRequiresCompletedAtAndCannotBeCompleted() {
        assertThrows(IllegalArgumentException.class, () -> new IndexingRun(
                UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.STAGING, CREATED, Optional.empty(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                IndexingRun.CURRENT_FORMAT_VERSION));
        assertThrows(IllegalArgumentException.class, () -> new IndexingRun(
                UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.COMPLETED, CREATED, Optional.of(OBSERVED), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                IndexingRun.CURRENT_FORMAT_VERSION));
    }

    @Test
    void projectStateCarriesAtMostOneResumableRunOnlyWhileStaleOrFailed() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ProjectIndexState stale = new ProjectIndexState(projectId, ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-old"), Optional.of(runId), OBSERVED, Optional.of("interrupted"),
                Optional.of(runId));
        ProjectIndexState failed = new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(runId), OBSERVED, Optional.of("interrupted"), Optional.of(runId));

        assertEquals(Optional.of(runId), stale.resumableRunId());
        assertEquals(Optional.of(runId), failed.resumableRunId());
        assertThrows(IllegalArgumentException.class, () -> new ProjectIndexState(projectId,
                ProjectIndexState.Availability.READY, Optional.of("snapshot"), Optional.of(runId), OBSERVED,
                Optional.empty(), Optional.of(runId)));
        assertThrows(IllegalArgumentException.class, () -> new ProjectIndexState(projectId,
                ProjectIndexState.Availability.REFRESHING, Optional.of("snapshot"), Optional.of(runId), OBSERVED,
                Optional.empty(), Optional.of(runId)));
    }

    @Test
    void compatibilityConstructorCarriesNoResumableRun() {
        ProjectIndexState state = new ProjectIndexState(UUID.randomUUID(), ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-old"), Optional.empty(), OBSERVED, Optional.empty());

        assertEquals(Optional.empty(), state.resumableRunId());
        assertEquals(Optional.empty(), ProjectIndexState.neverIndexed(UUID.randomUUID(), OBSERVED).resumableRunId());
    }
}
