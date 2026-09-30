package com.minos.orchestration;

import com.minos.orchestration.IndexingResumeTest.Fixture;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.minos.orchestration.IndexingResumeTest.negotiation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 : le répertoire d'un run en cours est retenu par le marqueur dès avant son premier provider, pour
 * qu'une seconde indexation (un autre processus) ne puisse pas l'effacer, et le marqueur est levé
 * quand le run se termine, quel qu'en soit le résultat, sauf s'il reste offert à la reprise.
 */
class RunDirectoryHoldTest {

    private static final Instant T0 = Instant.parse("2026-09-30T08:00:00Z");

    @Test
    void aFreshRunHoldsItsDirectoryBeforeItsFirstProviderAndLiftsTheHoldOnceSucceeded(@TempDir Path temp)
            throws Exception {
        Fixture fixture = new Fixture(temp);
        List<Boolean> heldWhenProviderStarted = new ArrayList<>();
        Fixture.Executor executor = new Fixture.Executor(fixture, 99) {
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                heldWhenProviderStarted.add(fixture.markers.marked.contains(request.runId())
                        && !fixture.markers.unmarked.contains(request.runId()));
                return super.execute(request);
            }
        };

        IndexingRun run = fixture.lifecycle(executor, T0).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(List.of(true, true, true), heldWhenProviderStarted,
                "the run directory is held before every provider starts");
        assertTrue(fixture.markers.unmarked.contains(run.id()), "the hold is lifted when the run is terminal");
    }

    @Test
    void aFailedRunLiftsItsHold(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);

        IndexingRun run = fixture.lifecycle(new Fixture.Executor(fixture, 0), T0).execute(
                fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertTrue(fixture.markers.marked.contains(run.id()));
        assertTrue(fixture.markers.unmarked.contains(run.id()), "a failed run must not stay held until its expiry");
    }

    @Test
    void anInterruptedRunKeepsItsMarkerBecauseItIsOfferedForResume(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        Fixture.Executor executor = new Fixture.Executor(fixture, 99) {
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                if (!executed.isEmpty()) throw new InterruptedException("service is stopping");
                return super.execute(request);
            }
        };

        IndexingRun run;
        try {
            run = fixture.lifecycle(executor, T0).execute(
                    fixture.projectId, fixture.root, fixture.discovery(), negotiation());
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag is replayed to the caller");
        }

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status());
        assertTrue(fixture.markers.marked.contains(run.id()));
        assertFalse(fixture.markers.unmarked.contains(run.id()), "the marker keeps protecting the offered run");
        assertEquals(Optional.of(run.id()), fixture.store.findProjectState(fixture.projectId).orElseThrow().resumableRunId());
    }

    @Test
    void aHoldThatCannotBeWrittenNeverFailsTheRun(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        ResumableRunMarkers unwritable = new ResumableRunMarkers() {
            @Override public void mark(UUID runId) throws IOException {
                throw new IOException("marker directory is not writable");
            }
            @Override public void unmark(UUID runId) { }
            @Override public Optional<Path> runDirectory(UUID runId) {
                return Optional.of(fixture.home.resolve("runs").resolve(runId.toString()));
            }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(new Fixture.Executor(fixture, 99)), fixture.stager, fixture.promoter, fixture.store, unwritable,
                ResumableArtifactPolicy.DEFAULT, Clock.fixed(T0, ZoneOffset.UTC));

        IndexingRun run = lifecycle.execute(fixture.projectId, fixture.root, fixture.discovery(), negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status(),
                "the hold only protects retention: without it the run is exactly what it was before");
    }
}
