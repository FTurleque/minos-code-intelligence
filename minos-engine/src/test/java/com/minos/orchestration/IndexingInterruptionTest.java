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
import java.util.ArrayList;
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

    @Test
    void aSecondInterruptionWhileTheInterruptedStateIsBeingWrittenStillReachesTheStore(@TempDir Path temp)
            throws Exception {
        // V-L4-02: a service that is stopping can interrupt the thread twice. The second interruption lands
        // on the write of the INTERRUPTED run itself; the write must be made again, flag cleared, and what
        // the caller is told must be what the store holds.
        Fixture fixture = new Fixture(temp);
        FlagSensitiveStore store = new FlagSensitiveStore(fixture.store);
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(interruptedAfterTwoTargets(fixture)), fixture.stager, fixture.promoter, store,
                fixture.markers, ResumableArtifactPolicy.DEFAULT, Clock.fixed(T0, ZoneOffset.UTC));

        IndexingRun run = executeReplayingTheFlag(fixture, lifecycle);

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status(), String.valueOf(run.message()));
        assertTrue(store.secondInterruptionDelivered, "the test delivered its second interruption");
        IndexingRun held = fixture.store.findRun(run.id()).orElseThrow();
        assertEquals(IndexingRun.Status.INTERRUPTED, held.status(), "the store holds what the caller was told");
        assertOfferedForResume(fixture, held, 2);
    }

    @Test
    void aSecondInterruptionWhileTheMarkerIsBeingHeldStillOffersTheRunForResume(@TempDir Path temp)
            throws Exception {
        Fixture fixture = new Fixture(temp);
        List<UUID> held = new ArrayList<>();
        ResumableRunMarkers interruptedMarkers = new ResumableRunMarkers() {
            private int calls;

            @Override
            public void mark(UUID runId) throws IOException {
                // The first call holds the directory when the run starts; the second is the interruption's.
                if (++calls == 2) Thread.currentThread().interrupt();
                if (Thread.currentThread().isInterrupted()) throw new IOException("marker", new ClosedByInterruptException());
                held.add(runId);
            }

            @Override public void unmark(UUID runId) { fixture.markers.unmark(runId); }

            @Override public Optional<Path> runDirectory(UUID runId) { return fixture.markers.runDirectory(runId); }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(interruptedAfterTwoTargets(fixture)), fixture.stager, fixture.promoter, fixture.store,
                interruptedMarkers, ResumableArtifactPolicy.DEFAULT, Clock.fixed(T0, ZoneOffset.UTC));

        IndexingRun run = executeReplayingTheFlag(fixture, lifecycle);

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status(), String.valueOf(run.message()));
        assertEquals(List.of(run.id(), run.id()), held, "the run directory is held again once the flag is cleared");
        assertOfferedForResume(fixture, run, 2);
    }

    /** Deux cibles se terminent, la troisième est interrompue comme le ferait l'arrêt du service. */
    private static IndexerExecutor interruptedAfterTwoTargets(Fixture fixture) {
        return new Fixture.Executor(fixture, 99) {
            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                if (executed.size() >= 2) throw new InterruptedException("service is stopping");
                return super.execute(request);
            }
        };
    }

    /**
     * Un magasin qui se comporte comme un canal de fichier : avec le drapeau d'interruption levé, une écriture
     * échoue en {@code ClosedByInterruptException}. La première écriture d'un run INTERRUPTED reçoit, juste
     * avant, la seconde interruption.
     */
    private static final class FlagSensitiveStore implements IndexStateStore {
        private final IndexStateStore delegate;
        private boolean secondInterruptionDelivered;

        private FlagSensitiveStore(IndexStateStore delegate) {
            this.delegate = delegate;
        }

        @Override public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return delegate.findProjectState(projectId);
        }

        @Override public Optional<IndexingRun> findRun(UUID runId) { return delegate.findRun(runId); }

        @Override public List<IndexingRun> listRuns(UUID projectId) { return delegate.listRuns(projectId); }

        @Override public ProjectLease acquireProjectLease(UUID projectId) {
            return delegate.acquireProjectLease(projectId);
        }

        @Override public void saveProjectState(ProjectIndexState state) {
            failWhenInterrupted();
            delegate.saveProjectState(state);
        }

        @Override public void saveRun(IndexingRun run) {
            if (run.status() == IndexingRun.Status.INTERRUPTED && !secondInterruptionDelivered) {
                secondInterruptionDelivered = true;
                Thread.currentThread().interrupt();
            }
            failWhenInterrupted();
            delegate.saveRun(run);
        }

        private static void failWhenInterrupted() {
            if (Thread.currentThread().isInterrupted()) {
                throw new UncheckedIOException("write interrupted", new ClosedByInterruptException());
            }
        }
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
