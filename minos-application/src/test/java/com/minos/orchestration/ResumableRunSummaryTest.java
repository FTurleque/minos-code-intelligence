package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : résumé du run reprenable exposé par la CLI, MCP et l'API. */
class ResumableRunSummaryTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant FIRST = Instant.parse("2026-09-26T08:05:00Z");
    private static final Instant LAST = Instant.parse("2026-09-26T08:10:00Z");
    private static final Instant INTERRUPTED_AT = Instant.parse("2026-09-26T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-26T09:10:00Z");

    @Test
    void summarizesTheInterruptedRunReferencedByTheProjectState() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveRun(new IndexingRun(runId, projectId, IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.of(INTERRUPTED_AT),
                List.of(checkpointed("a", FIRST), checkpointed("b", LAST),
                        new IndexerExecution(Language.GO, "scip-go", Path.of("go.scip"))),
                Optional.empty(), Optional.of("snapshot-old"), Optional.of("snapshot-old"),
                Optional.of("interrupted"), IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-old"), Optional.of(runId), INTERRUPTED_AT, Optional.of("interrupted"),
                Optional.of(runId)));

        ResumableRunSummary summary = ResumableRunSummary.of(store, projectId).orElseThrow();

        assertEquals(runId, summary.runId());
        assertEquals(IndexingRun.Phase.PROVIDER_EXECUTION, summary.phase());
        assertEquals(LAST, summary.checkpointAt(), "the age is that of the most recent checkpoint");
        assertEquals(2, summary.resumableTargets());
        assertEquals(3, summary.completedExecutions());
        assertEquals(3600L, summary.checkpointAgeSeconds(NOW));
    }

    @Test
    void stagedOnlyInterruptionIsDatedFromTheInterruption() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveRun(new IndexingRun(runId, projectId, IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROMOTION, CREATED, Optional.of(INTERRUPTED_AT), List.of(),
                Optional.of("snapshot-staged"), Optional.empty(), Optional.empty(),
                Optional.of("interrupted"), IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(runId), INTERRUPTED_AT, Optional.of("interrupted"),
                Optional.of(runId)));

        ResumableRunSummary summary = ResumableRunSummary.of(store, projectId).orElseThrow();

        assertEquals(INTERRUPTED_AT, summary.checkpointAt());
        assertEquals(0, summary.resumableTargets());
        assertEquals(IndexingRun.Phase.PROMOTION, summary.phase());
    }

    @Test
    void inconsistentResumableReferenceIsIgnored() {
        UUID projectId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.empty(), INTERRUPTED_AT, Optional.of("x"), Optional.of(missing)));
        assertTrue(ResumableRunSummary.of(store, projectId).isEmpty(), "a missing run is ignored (R1-9)");

        store.saveRun(new IndexingRun(failedId, projectId, IndexingRun.Status.FAILED,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.of(INTERRUPTED_AT), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("failed"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(failedId), INTERRUPTED_AT, Optional.of("x"), Optional.of(failedId)));
        assertTrue(ResumableRunSummary.of(store, projectId).isEmpty(), "a run that is not INTERRUPTED is ignored");

        assertTrue(ResumableRunSummary.of(store, UUID.randomUUID()).isEmpty(), "an unknown project has nothing");
    }

    private static IndexerExecution checkpointed(String scope, Instant completedAt) {
        return new IndexerExecution(Language.TYPESCRIPT, "scip-typescript", Path.of("index.scip"), Optional.of(
                new ExecutionCheckpoint(Path.of(scope), "0.4.0", 42L, "a".repeat(64), "b".repeat(64),
                        IndexingMode.FULL, List.of(), completedAt)));
    }
}
