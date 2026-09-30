package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : récupération d'un run abandonné en INTERRUPTED (ADR 0039 §2). */
class InterruptedRunRecoveryTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant CHECKPOINT = Instant.parse("2026-09-26T08:05:00Z");
    private static final Instant RECOVERED = Instant.parse("2026-09-26T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(RECOVERED, ZoneOffset.UTC);

    @Test
    void abandonedRunWithACheckpointBecomesInterruptedAndTheProjectOffersItForResume() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        RecordingMarkers markers = new RecordingMarkers(events, false);
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                List.of(checkpointed("ui/app")), Optional.empty(), Optional.of("snapshot-old"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, runId, "snapshot-old"));
        events.clear();

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store, markers, RECOVERED, "restart");

        IndexingRun run = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.INTERRUPTED, run.status());
        assertEquals(IndexingRun.Phase.PROVIDER_EXECUTION, run.phase(), "the interrupted phase is preserved");
        assertEquals(Optional.of(RECOVERED), run.completedAt());
        assertEquals(Optional.of("snapshot-old"), run.activeSnapshotAfter());
        assertEquals(1, run.executions().size(), "checkpoints survive the transition");
        assertEquals(ProjectIndexState.Availability.STALE, recovered.availability());
        assertEquals(Optional.of(runId), recovered.resumableRunId());
        assertEquals(Optional.of(runId), recovered.latestRunId());
        assertEquals(List.of("mark:" + runId, "saveRun:" + runId + ":INTERRUPTED", "saveProjectState:STALE"),
                events.subList(0, 3), "marker first, then the run, then the project state");
    }

    @Test
    void abandonedRunWithoutCheckpointOrStagedSnapshotStillFailsWithoutAnyMarker() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        RecordingMarkers markers = new RecordingMarkers(events, false);
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                List.of(new IndexerExecution(Language.JAVA, "scip-java", Path.of("index.scip"))),
                Optional.empty(), Optional.of("snapshot-old"), IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, runId, "snapshot-old"));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store, markers, RECOVERED, "restart");

        assertEquals(IndexingRun.Status.FAILED, store.findRun(runId).orElseThrow().status());
        assertEquals(ProjectIndexState.Availability.STALE, recovered.availability());
        assertEquals(Optional.empty(), recovered.resumableRunId());
        assertTrue(events.stream().noneMatch(event -> event.startsWith("mark:")));
        assertTrue(events.contains("unmark:" + runId),
                "R5: the hold a crashed run left behind is lifted when recovery finalizes it as failed");
    }

    @Test
    void abandonedStagingRunWithAStagedSnapshotIsInterruptedEvenWithoutCheckpoints() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROMOTION,
                List.of(new IndexerExecution(Language.JAVA, "scip-java", Path.of("index.scip"))),
                Optional.of("snapshot-staged"), Optional.empty(), IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.INDEXING,
                Optional.empty(), Optional.of(runId), CREATED, Optional.of("first index")));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, noActivePromoter(), store, new RecordingMarkers(events, false), RECOVERED, "restart");

        assertEquals(IndexingRun.Status.INTERRUPTED, store.findRun(runId).orElseThrow().status());
        assertEquals(ProjectIndexState.Availability.FAILED, recovered.availability(),
                "without an active snapshot the project is FAILED but still offers the resume");
        assertEquals(Optional.of(runId), recovered.resumableRunId());
    }

    @Test
    void authoritativePromotionRecoveryKeepsPriorityOverInterruption() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROMOTION,
                List.of(checkpointed("")), Optional.of("snapshot-new"), Optional.of("snapshot-old"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, runId, "snapshot-old"));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-new")), store,
                new RecordingMarkers(events, false), RECOVERED, "restart");

        IndexingRun run = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(ProjectIndexState.Availability.READY, recovered.availability());
        assertEquals(Optional.empty(), recovered.resumableRunId());
        assertTrue(events.stream().noneMatch(event -> event.startsWith("mark:")));
        assertTrue(events.contains("unmark:" + runId), "R5: a recovered success no longer needs its hold");
    }

    @Test
    void legacyFormatRunIsNeverInterruptedEvenWithAStagedSnapshot() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROMOTION,
                List.of(new IndexerExecution(Language.JAVA, "scip-java", Path.of("index.scip"))),
                Optional.of("snapshot-staged"), Optional.of("snapshot-old"), IndexingRun.LEGACY_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, runId, "snapshot-old"));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store,
                new RecordingMarkers(events, false), RECOVERED, "restart");

        assertEquals(IndexingRun.Status.FAILED, store.findRun(runId).orElseThrow().status());
        assertEquals(Optional.empty(), recovered.resumableRunId());
    }

    @Test
    void markerWriteFailureFailsClosedToFailedWithoutOfferingAResume() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                List.of(checkpointed("ui/app")), Optional.empty(), Optional.of("snapshot-old"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, runId, "snapshot-old"));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store,
                new RecordingMarkers(events, true), RECOVERED, "restart");

        IndexingRun run = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertTrue(run.message().orElseThrow().contains("resumable marker"), run.message().orElse(""));
        assertEquals(Optional.empty(), recovered.resumableRunId());
        assertTrue(events.contains("unmark:" + runId), "V13: a marker that could not be written is removed best-effort");
    }

    @Test
    void missingPersistedReferenceIsDerivedFromTheNewestInterruptedRun() {
        // V11: crash between saveRun(INTERRUPTED) and saveProjectState leaves the project in progress
        // without a reference; the marked run must be offered, never left as an orphan.
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(new IndexingRun(runId, projectId, IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.of(CHECKPOINT), List.of(checkpointed("")),
                Optional.empty(), Optional.of("snapshot-old"), Optional.of("snapshot-old"), Optional.of("interrupted"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, runId, "snapshot-old"));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store,
                new RecordingMarkers(events, false), RECOVERED, "restart");

        assertEquals(ProjectIndexState.Availability.STALE, recovered.availability());
        assertEquals(Optional.of(runId), recovered.resumableRunId());
        assertEquals(IndexingRun.Status.INTERRUPTED, store.findRun(runId).orElseThrow().status());
    }

    @Test
    void currentProjectSupersedesAnOfferedRunInsteadOfLeavingItMarked() {
        // V12: two abandoned runs, the newer one already promoted; the older interrupted run is
        // finalized and unmarked because a READY project has nothing to resume.
        UUID projectId = UUID.randomUUID();
        UUID olderId = UUID.randomUUID();
        UUID promotedId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(running(olderId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                List.of(checkpointed("ui/app")), Optional.empty(), Optional.of("snapshot-old"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveRun(new IndexingRun(promotedId, projectId, IndexingRun.Status.RUNNING, IndexingRun.Phase.PROMOTION,
                CREATED.plusSeconds(60), Optional.empty(), List.of(checkpointed("")), Optional.of("snapshot-new"),
                Optional.of("snapshot-old"), Optional.of("snapshot-old"), Optional.of("promoting"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, promotedId, "snapshot-old"));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-new")), store,
                new RecordingMarkers(events, false), RECOVERED, "restart");

        assertEquals(ProjectIndexState.Availability.READY, recovered.availability());
        assertEquals(Optional.empty(), recovered.resumableRunId());
        assertEquals(IndexingRun.Status.SUCCEEDED, store.findRun(promotedId).orElseThrow().status());
        IndexingRun older = store.findRun(olderId).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, older.status());
        assertTrue(older.message().orElseThrow().contains("superseded"), older.message().orElse(""));
        assertTrue(events.contains("unmark:" + olderId));
    }

    @Test
    void stableSnapshotRepairSupersedesTheOfferedRunAndLiftsItsMarker() {
        // R7: the persisted state offers an interrupted run but references an older snapshot than the
        // authoritative one. The repair makes the project READY (which never offers a resume); it used
        // to drop the reference silently, leaving the run INTERRUPTED and marked, out of retention's
        // reach and able to be offered again later.
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveRun(new IndexingRun(runId, projectId, IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.of(CHECKPOINT), List.of(checkpointed("ui/app")),
                Optional.empty(), Optional.of("snapshot-old"), Optional.of("snapshot-old"), Optional.of("interrupted"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-old"), Optional.of(runId), CREATED, Optional.of("interrupted run offered"),
                Optional.of(runId)));
        events.clear();

        ProjectIndexState repaired = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-new")), store,
                new RecordingMarkers(events, false), RECOVERED, "restart");

        assertEquals(ProjectIndexState.Availability.READY, repaired.availability());
        assertEquals(Optional.of("snapshot-new"), repaired.activeSnapshotId());
        assertEquals(Optional.empty(), repaired.resumableRunId(), "a current project offers nothing to resume");
        IndexingRun superseded = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, superseded.status(), "the offered run is finalized, not left INTERRUPTED");
        assertTrue(superseded.message().orElseThrow().contains("superseded"), superseded.message().orElse(""));
        assertTrue(events.contains("unmark:" + runId), "its marker is lifted so retention can reclaim the directory");
        assertEquals(Optional.empty(), store.findProjectState(projectId).orElseThrow().resumableRunId());
    }

    @Test
    void stableSnapshotRepairWithoutAnOfferedRunTouchesNoRunAndNoMarker() {
        UUID projectId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), CREATED, Optional.of("baseline")));
        events.clear();

        ProjectIndexState repaired = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-new")), store,
                new RecordingMarkers(events, false), RECOVERED, "restart");

        assertEquals(Optional.of("snapshot-new"), repaired.activeSnapshotId());
        assertTrue(events.stream().noneMatch(event -> event.startsWith("saveRun:") || event.startsWith("unmark:")), events.toString());
    }

    @Test
    void threadInterruptionDuringAProviderLeavesAnInterruptedRunAndRestoresTheFlag(@TempDir Path root)
            throws Exception {
        // R1-11: an InterruptedException is not an explicit failure; with a checkpoint already
        // persisted the run is offered for resume and the interrupt flag is replayed.
        UUID projectId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        RecordingMarkers markers = new RecordingMarkers(events, false);
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), CREATED, Optional.of("baseline")));
        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        IndexerExecutor interrupting = new IndexerExecutor() {
            @Override public String indexerId() { return "scip-go"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                throw new InterruptedException("simulated shutdown signal");
            }
        };
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(executor(artifact), interrupting), request -> "snapshot-next",
                promoter(new AtomicReference<>("snapshot-old")), store, markers, CLOCK);
        IndexerNegotiationResult negotiation = new IndexerNegotiationResult(List.of(selection(),
                new IndexerSelection(Language.GO, new IndexerDescriptor("scip-go", "0.1.0", "scip-go",
                        Set.of(Language.GO), Set.of(), EnumSet.of(IndexerCapability.SYMBOLS),
                        IndexerQualification.QUALIFIED, 100, List.of()))), Set.of(), List.of());

        IndexingRun run;
        try {
            run = service.execute(projectId, root, negotiation);
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be replayed to the caller");
        }

        assertEquals(IndexingRun.Status.INTERRUPTED, run.status());
        assertEquals(1, run.executions().size());
        assertTrue(events.contains("mark:" + run.id()));
        ProjectIndexState state = store.findProjectState(projectId).orElseThrow();
        assertEquals(ProjectIndexState.Availability.STALE, state.availability());
        assertEquals(Optional.of(run.id()), state.resumableRunId());
    }

    @Test
    void threadInterruptionWithoutAnyCheckpointStillFails(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        IndexerExecutor interrupting = new IndexerExecutor() {
            @Override public String indexerId() { return "scip-typescript"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                throw new InterruptedException("simulated shutdown signal");
            }
        };
        IndexingLifecycleService service = new IndexingLifecycleService(List.of(interrupting),
                request -> "snapshot-next", promoter(new AtomicReference<>()), store,
                new RecordingMarkers(events, false), CLOCK);

        IndexingRun run;
        try {
            run = service.execute(projectId, root, new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));
        } finally {
            assertTrue(Thread.interrupted());
        }

        assertEquals(IndexingRun.Status.FAILED, run.status());
        // R5: the run was held against retention while it ran; a failed run offers nothing, so the hold
        // is lifted with it and no marker outlives the run.
        List<String> markerEvents = events.stream()
                .filter(event -> event.startsWith("mark:") || event.startsWith("unmark:")).toList();
        assertEquals("unmark:" + run.id(), markerEvents.getLast(), markerEvents.toString());
        assertEquals(Optional.empty(), store.findProjectState(projectId).orElseThrow().resumableRunId());
    }

    @Test
    void interruptedRunNeverBlocksANewRunWhichSupersedesIt(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID interruptedId = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        RecordingStore store = new RecordingStore(events);
        RecordingMarkers markers = new RecordingMarkers(events, false);
        store.saveRun(running(interruptedId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                List.of(checkpointed("")), Optional.empty(), Optional.of("snapshot-old"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        store.saveProjectState(refreshing(projectId, interruptedId, "snapshot-old"));
        AtomicReference<String> active = new AtomicReference<>("snapshot-old");
        SnapshotPromoter promoter = promoter(active);
        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(executor(artifact)), request -> "snapshot-next", promoter, store, markers, CLOCK);

        // A project-state read sees the interruption and keeps offering the resume (R1-12: not blocking).
        ProjectIndexState observed = service.recoverProjectState(projectId);
        assertEquals(ProjectIndexState.Availability.STALE, observed.availability());
        assertEquals(Optional.of(interruptedId), observed.resumableRunId());
        assertEquals(Optional.of(interruptedId), service.recoverProjectState(projectId).resumableRunId(),
                "a second read must not alter the offered resume");

        IndexingRun next = service.execute(projectId, root,
                new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));

        assertEquals(IndexingRun.Status.SUCCEEDED, next.status());
        IndexingRun superseded = store.findRun(interruptedId).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, superseded.status());
        assertTrue(superseded.message().orElseThrow().contains("superseded"), superseded.message().orElse(""));
        assertTrue(events.contains("unmark:" + interruptedId), "the marker is removed when the run becomes terminal");
        ProjectIndexState after = store.findProjectState(projectId).orElseThrow();
        assertEquals(ProjectIndexState.Availability.READY, after.availability());
        assertEquals(Optional.empty(), after.resumableRunId());
        assertEquals(Optional.of(next.id()), after.latestRunId());
    }

    private static IndexingRun running(UUID runId, UUID projectId, IndexingRun.Phase phase,
                                       List<IndexerExecution> executions, Optional<String> staged,
                                       Optional<String> before, int formatVersion) {
        return new IndexingRun(runId, projectId, IndexingRun.Status.RUNNING, phase, CREATED, Optional.empty(),
                executions, staged, before, before, Optional.of("in progress"), formatVersion);
    }

    private static IndexerExecution checkpointed(String scope) {
        return new IndexerExecution(Language.TYPESCRIPT, "scip-typescript", Path.of("index.scip"), Optional.of(
                new ExecutionCheckpoint(Path.of(scope), "0.4.0", 42L, "a".repeat(64), "b".repeat(64),
                        IndexingMode.FULL, List.of(), CHECKPOINT)));
    }

    private static ProjectIndexState refreshing(UUID projectId, UUID runId, String snapshot) {
        return new ProjectIndexState(projectId, ProjectIndexState.Availability.REFRESHING, Optional.of(snapshot),
                Optional.of(runId), CREATED, Optional.of("refresh in progress"));
    }

    private static SnapshotPromoter promoter(AtomicReference<String> active) {
        return new SnapshotPromoter() {
            @Override public void promote(UUID projectId, UUID runId, String stagedSnapshotId) {
                active.set(stagedSnapshotId);
            }
            @Override public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
                String current = active.get();
                return current == null
                        ? ActiveSnapshotObservation.noActiveSnapshot()
                        : ActiveSnapshotObservation.active(current);
            }
        };
    }

    private static SnapshotPromoter noActivePromoter() {
        return promoter(new AtomicReference<>());
    }

    private static IndexerExecutor executor(Path artifact) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return "scip-typescript"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                return new IndexingArtifact(Language.TYPESCRIPT, "scip-typescript", artifact);
            }
        };
    }

    private static IndexerSelection selection() {
        return new IndexerSelection(Language.TYPESCRIPT, new IndexerDescriptor("scip-typescript", "0.4.0",
                "scip-typescript", Set.of(Language.TYPESCRIPT), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 100, List.of()));
    }

    /** In-memory store that records the order of durable writes. */
    private static final class RecordingStore implements IndexStateStore {
        private final InMemoryIndexStateStore delegate = new InMemoryIndexStateStore();
        private final List<String> events;

        private RecordingStore(List<String> events) { this.events = events; }

        @Override public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return delegate.findProjectState(projectId);
        }
        @Override public Optional<IndexingRun> findRun(UUID runId) { return delegate.findRun(runId); }
        @Override public List<IndexingRun> listRuns(UUID projectId) { return delegate.listRuns(projectId); }
        @Override public void saveProjectState(ProjectIndexState state) {
            events.add("saveProjectState:" + state.availability());
            delegate.saveProjectState(state);
        }
        @Override public void saveRun(IndexingRun run) {
            events.add("saveRun:" + run.id() + ":" + run.status());
            delegate.saveRun(run);
        }
        @Override public ProjectLease acquireProjectLease(UUID projectId) {
            return delegate.acquireProjectLease(projectId);
        }
    }

    private static final class RecordingMarkers implements ResumableRunMarkers {
        private final List<String> events;
        private final boolean failOnMark;

        private RecordingMarkers(List<String> events, boolean failOnMark) {
            this.events = events;
            this.failOnMark = failOnMark;
        }

        @Override public void mark(UUID runId) throws IOException {
            if (failOnMark) throw new IOException("marker directory is not writable");
            events.add("mark:" + runId);
        }
        @Override public void unmark(UUID runId) {
            events.add("unmark:" + runId);
        }
    }
}
