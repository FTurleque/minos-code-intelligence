package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingResumeTest.Fixture;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q5 et R6 : une interruption reste une interruption de bout en bout, quelle que soit la forme sous
 * laquelle elle arrive (attente de lisibilité de l'artefact, promotion d'une reprise, canal fermé par
 * l'interruption). Le run se termine en INTERRUPTED, offert à la reprise avec ses points de contrôle, et
 * le drapeau d'interruption du thread est rétabli pour l'appelant.
 */
class IndexingInterruptionTest {

    private static final String PROVIDER = "scip-typescript";
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @Test
    void anInterruptionPendingWhenTheArtifactWaitBeginsLeavesAnInterruptedResumableRun(@TempDir Path temp)
            throws Exception {
        Fixture fixture = new Fixture(temp);
        IndexerExecutor returnsAnUnreadableArtifactUnderInterruption = new Fixture.Executor(fixture, 99) {
            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                if (executed.isEmpty()) return super.execute(request);
                Thread.currentThread().interrupt();
                return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, fixture.home.resolve("never-written.scip"),
                        request.projectRelativeRoot());
            }
        };

        IndexingRun run = executeReplayingTheFlag(fixture,
                fixture.lifecycle(returnsAnUnreadableArtifactUnderInterruption, T0));

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status(), String.valueOf(run.message()));
        assertOfferedForResume(fixture, run, 1);
        assertFalse(fixture.markers.unmarked.contains(run.id()), "the hold is kept: the run is still resumable");
    }

    @Test
    void anInterruptionDuringTheResumedPromotionKeepsTheRunInterruptedInsteadOfFallingBackToAFullRun(
            @TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.active.set("snapshot-old");
        IndexingRun interrupted = fixture.interruptInPhase(
                IndexingRun.Phase.PROMOTION, Optional.of("snapshot-staged"), T0);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99);
        SnapshotPromoter interruptedPromoter = new SnapshotPromoter() {
            @Override
            public void promote(UUID id, UUID runId, String staged) throws InterruptedException {
                throw new InterruptedException("service is stopping");
            }

            @Override
            public ActiveSnapshotObservation observeActiveSnapshot(UUID id) throws IOException {
                return fixture.promoter.observeActiveSnapshot(id);
            }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(List.of(executor), fixture.stager,
                interruptedPromoter, fixture.store, fixture.markers, ResumableArtifactPolicy.DEFAULT,
                Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC));

        IndexingRun run = executeReplayingTheFlag(fixture, lifecycle);

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status(), String.valueOf(run.message()));
        assertEquals(interrupted.id(), run.id(), "the same run stays offered: no fallback to a new full run");
        assertTrue(executor.executed.isEmpty(), "no provider is relaunched by a fallback");
        assertOfferedForResume(fixture, run, 3);
    }

    @Test
    void aChannelClosedByTheInterruptionDuringStagingKeepsTheRunInterrupted(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        SnapshotStager interruptedStager = new SnapshotStager() {
            @Override
            public String stage(IndexSnapshotStageRequest request) {
                throw new UncheckedIOException("staging read was interrupted", new ClosedByInterruptException());
            }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(new Fixture.Executor(fixture, 99)), interruptedStager, fixture.promoter, fixture.store,
                fixture.markers, ResumableArtifactPolicy.DEFAULT, Clock.fixed(T0, ZoneOffset.UTC));

        IndexingRun run = executeReplayingTheFlag(fixture, lifecycle);

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status(), String.valueOf(run.message()));
        assertEquals(IndexingRun.Phase.STAGING, run.phase());
        assertOfferedForResume(fixture, run, 3);
        assertFalse(fixture.markers.unmarked.contains(run.id()), "the hold is kept: the run is still resumable");
    }

    /** Exécute, puis constate que le drapeau d'interruption a été rétabli (et le rend au thread de test). */
    private static IndexingRun executeReplayingTheFlag(Fixture fixture, IndexingLifecycleService lifecycle) {
        IndexingRun run;
        try {
            run = lifecycle.execute(fixture.projectId, fixture.root, fixture.discovery(),
                    IndexingResumeTest.negotiation());
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be replayed to the caller");
        }
        return run;
    }

    private static void assertOfferedForResume(Fixture fixture, IndexingRun run, int expectedCheckpoints) {
        long checkpoints = run.executions().stream()
                .map(IndexingRun.IndexerExecution::checkpoint).filter(Optional::isPresent).count();
        assertEquals(expectedCheckpoints, checkpoints, "the completed targets keep their checkpoint");
        ProjectIndexState state = fixture.store.findProjectState(fixture.projectId).orElseThrow();
        assertEquals(Optional.of(run.id()), state.resumableRunId(), "the project offers the run for resume");
    }
}
