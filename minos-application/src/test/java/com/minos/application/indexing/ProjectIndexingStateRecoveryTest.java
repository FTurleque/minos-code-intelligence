package com.minos.application.indexing;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted state of a project, as the indexing use case reads and repairs it: a plan fails closed on a state
 * whose snapshot the store does not have, and the recovery of a promoted run keeps the run format and checkpoints
 * (R1, V6).
 */
class ProjectIndexingStateRecoveryTest {

    @TempDir Path temp;

    @Test
    void planFailsClosedWhenPersistedStateReferencesMissingAuthoritativeSnapshot() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexState ghostState = new ProjectIndexState(fixture.project.id(),
                ProjectIndexState.Availability.READY, Optional.of("ghost-snapshot"), Optional.empty(),
                Instant.parse("2026-08-14T12:00:00Z"), Optional.of("fixture persisted state"));
        fixture.stateStore.saveProjectState(ghostState);
        ProjectIndexingUseCase useCase = fixture.useCase();
        String projectId = fixture.id();

        IOException failure = assertThrows(IOException.class, () -> useCase.planSummary(projectId, null, false));

        assertTrue(failure.getMessage().contains("snapshot store has none"), failure.getMessage());
        assertEquals(ghostState, fixture.stateStore.findProjectState(fixture.project.id()).orElseThrow(),
                "fail-closed planning must preserve the corrupt evidence for diagnosis/recovery");
    }

    @Test
    void recoveringAPromotedRunKeepsItsFormatVersionAndCheckpoints() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.snapshots.active.put(fixture.project.id(), "snapshot-promoted");
        IndexingRun failed = new IndexingRun(
                UUID.randomUUID(), fixture.project.id(), IndexingRun.Status.FAILED, IndexingRun.Phase.PROMOTION,
                Instant.parse("2026-09-26T10:00:00Z"), Optional.of(Instant.parse("2026-09-26T10:01:00Z")),
                List.of(new IndexerExecution(Language.JAVA, IndexingFixture.INDEXER,
                        temp.resolve("index.scip").toAbsolutePath().normalize(), Optional.of(
                        new ExecutionCheckpoint(Path.of(""), "1.0", 12L, "a".repeat(64), "b".repeat(64),
                                IndexingMode.FULL, List.of(), Instant.parse("2026-09-26T10:00:30Z"))))),
                Optional.of("snapshot-promoted"), Optional.empty(), Optional.empty(),
                Optional.of("metadata finalization failed"), IndexingRun.CURRENT_FORMAT_VERSION);
        fixture.stateStore.saveRun(failed);

        Method recover = ProjectIndexingUseCase.class.getDeclaredMethod("recoverPromotedRunIfNeeded", IndexingRun.class);
        recover.setAccessible(true);
        IndexingRun recovered = (IndexingRun) recover.invoke(fixture.useCase(), failed);

        assertEquals(IndexingRun.Status.SUCCEEDED, recovered.status());
        assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, recovered.runFormatVersion(),
                "recovery must not silently downgrade a current-format run to the legacy format");
        assertTrue(recovered.executions().getFirst().checkpoint().isPresent());
        IndexingRun persisted = fixture.stateStore.findRun(failed.id()).orElseThrow();
        assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, persisted.runFormatVersion());
        assertEquals(Optional.of("snapshot-promoted"),
                fixture.stateStore.findProjectState(fixture.project.id()).orElseThrow().activeSnapshotId());
    }

    @Test
    void aProviderFailureOnAnIncrementalRunKeepsAFailingRetentionPassSuppressed() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectIndexingUseCase useCase = fixture.useCase();
        useCase.executeSummary(fixture.id(), null, false, IndexingResumeChoice.RESUME);
        java.nio.file.Files.writeString(fixture.root.resolve("src/main/java/App.java"), "class App { int changed; }");
        fixture.executor.failure = new IllegalStateException("executor reached");
        fixture.retentionFailure = new IOException("retention lock timed out");
        int compactionsBefore = fixture.compactions.get();
        String projectId = fixture.id();

        // Not forced and not complete: the run is the planned one, not the full one.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> useCase.executeSummary(projectId, null, false, IndexingResumeChoice.RESUME));

        assertTrue(failure.getMessage().startsWith("indexing run "), failure.getMessage());
        assertEquals(IndexingMode.INCREMENTAL, fixture.executor.lastRequest.get().mode());
        assertEquals(2, fixture.executor.calls.get(), "the changed project reached the provider");
        assertEquals(compactionsBefore + 1, fixture.compactions.get(), "the failed run is compacted");
        assertEquals(fixture.retentionFailure, failure.getSuppressed()[0]);
    }
}
