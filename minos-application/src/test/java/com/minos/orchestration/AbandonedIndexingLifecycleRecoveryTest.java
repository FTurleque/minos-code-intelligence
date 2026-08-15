package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbandonedIndexingLifecycleRecoveryTest {
    private static final Instant CREATED = Instant.parse("2026-08-15T08:00:00Z");
    private static final Instant RECOVERED = Instant.parse("2026-08-15T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(RECOVERED, ZoneOffset.UTC);

    @Test
    void abandonedFirstIndexIsFailedAndANewRunCanStart(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID abandonedRunId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveRun(running(abandonedRunId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                Optional.empty(), Optional.empty()));
        store.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.INDEXING,
                Optional.empty(),
                Optional.of(abandonedRunId),
                CREATED,
                Optional.of("crashed first index")));
        AtomicReference<String> active = new AtomicReference<>();
        SnapshotPromoter promoter = promoter(active);

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter, store, RECOVERED, "restart recovery");

        assertEquals(ProjectIndexState.Availability.FAILED, recovered.availability());
        assertEquals(Optional.empty(), recovered.activeSnapshotId());
        assertEquals(Optional.of(abandonedRunId), recovered.latestRunId());
        assertEquals(IndexingRun.Status.FAILED, store.findRun(abandonedRunId).orElseThrow().status());
        assertTrue(store.listRuns(projectId).stream().noneMatch(run -> run.status() == IndexingRun.Status.RUNNING));

        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        IndexingLifecycleService service = service(store, promoter, artifact);
        IndexingRun next = service.execute(
                projectId, root, new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));

        assertEquals(IndexingRun.Status.SUCCEEDED, next.status());
        assertEquals(Optional.of("snapshot-next"), store.findProjectState(projectId).orElseThrow().activeSnapshotId());
    }

    @Test
    void abandonedRefreshBecomesStaleWhenPreviousSnapshotRemainsActive() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                Optional.empty(), Optional.of("snapshot-old")));
        store.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.REFRESHING,
                Optional.of("snapshot-old"),
                Optional.of(runId),
                CREATED,
                Optional.of("refresh in progress")));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store, RECOVERED, "restart recovery");

        assertEquals(ProjectIndexState.Availability.STALE, recovered.availability());
        assertEquals(Optional.of("snapshot-old"), recovered.activeSnapshotId());
        IndexingRun failed = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.FAILED, failed.status());
        assertEquals(Optional.of("snapshot-old"), failed.activeSnapshotAfter());
    }

    @Test
    void abandonedPromotionIsRecoveredAsSuccessWhenStagedSnapshotIsAuthoritative() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveRun(running(runId, projectId, IndexingRun.Phase.PROMOTION,
                Optional.of("snapshot-new"), Optional.of("snapshot-old")));
        store.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.REFRESHING,
                Optional.of("snapshot-old"),
                Optional.of(runId),
                CREATED,
                Optional.of("promotion in progress")));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-new")), store, RECOVERED, "restart recovery");

        IndexingRun succeeded = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.SUCCEEDED, succeeded.status());
        assertEquals(IndexingRun.Phase.COMPLETED, succeeded.phase());
        assertEquals(Optional.of("snapshot-new"), succeeded.activeSnapshotAfter());
        assertEquals(ProjectIndexState.Availability.READY, recovered.availability());
        assertEquals(Optional.of("snapshot-new"), recovered.activeSnapshotId());
    }

    @Test
    void unreferencedRunningRunFromPartialStartupIsRecovered() {
        UUID projectId = UUID.randomUUID();
        UUID ghostRunId = UUID.randomUUID();
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"),
                Optional.empty(),
                CREATED,
                Optional.of("previously ready")));
        store.saveRun(running(ghostRunId, projectId, IndexingRun.Phase.PROVIDER_EXECUTION,
                Optional.empty(), Optional.of("snapshot-old")));

        ProjectIndexState recovered = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId, promoter(new AtomicReference<>("snapshot-old")), store, RECOVERED, "restart recovery");

        assertEquals(IndexingRun.Status.FAILED, store.findRun(ghostRunId).orElseThrow().status());
        assertEquals(ProjectIndexState.Availability.STALE, recovered.availability());
        assertEquals(Optional.of(ghostRunId), recovered.latestRunId());
    }

    @Test
    void failurePublishingInProgressProjectStateDoesNotLeaveRunningRun(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore durable = new InMemoryIndexStateStore();
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean providerExecuted = new AtomicBoolean();
        IndexStateStore failing = new IndexStateStore() {
            @Override public Optional<ProjectIndexState> findProjectState(UUID id) { return durable.findProjectState(id); }
            @Override public Optional<IndexingRun> findRun(UUID id) { return durable.findRun(id); }
            @Override public List<IndexingRun> listRuns(UUID id) { return durable.listRuns(id); }
            @Override public void saveProjectState(ProjectIndexState state) {
                if ((state.availability() == ProjectIndexState.Availability.INDEXING
                        || state.availability() == ProjectIndexState.Availability.REFRESHING)
                        && injected.compareAndSet(false, true)) {
                    throw new IllegalStateException("injected project-state write failure");
                }
                durable.saveProjectState(state);
            }
            @Override public void saveRun(IndexingRun run) { durable.saveRun(run); }
            @Override public ProjectLease acquireProjectLease(UUID id) { return durable.acquireProjectLease(id); }
        };
        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        AtomicReference<String> active = new AtomicReference<>();
        SnapshotPromoter promoter = promoter(active);
        IndexerExecutor executor = new IndexerExecutor() {
            @Override public String indexerId() { return "java-indexer"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                providerExecuted.set(true);
                return new IndexingArtifact(Language.JAVA, "java-indexer", artifact);
            }
        };
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(executor), request -> "snapshot-next", promoter, failing, CLOCK);

        IndexingRun result = service.execute(
                projectId, root, new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));

        assertTrue(injected.get());
        assertFalse(providerExecuted.get());
        assertEquals(IndexingRun.Status.FAILED, result.status());
        assertTrue(durable.listRuns(projectId).stream().noneMatch(run -> run.status() == IndexingRun.Status.RUNNING));
        assertEquals(ProjectIndexState.Availability.FAILED,
                durable.findProjectState(projectId).orElseThrow().availability());
    }

    private static IndexingLifecycleService service(
            IndexStateStore store,
            SnapshotPromoter promoter,
            Path artifact
    ) {
        IndexerExecutor executor = new IndexerExecutor() {
            @Override public String indexerId() { return "java-indexer"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                return new IndexingArtifact(Language.JAVA, "java-indexer", artifact);
            }
        };
        return new IndexingLifecycleService(
                List.of(executor), request -> "snapshot-next", promoter, store, CLOCK);
    }

    private static SnapshotPromoter promoter(AtomicReference<String> active) {
        return new SnapshotPromoter() {
            @Override
            public void promote(UUID projectId, UUID runId, String stagedSnapshotId) {
                active.set(stagedSnapshotId);
            }

            @Override
            public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
                return active.get() == null
                        ? ActiveSnapshotObservation.noActiveSnapshot()
                        : ActiveSnapshotObservation.active(active.get());
            }
        };
    }

    private static IndexingRun running(
            UUID runId,
            UUID projectId,
            IndexingRun.Phase phase,
            Optional<String> stagedSnapshot,
            Optional<String> activeBefore
    ) {
        return new IndexingRun(
                runId,
                projectId,
                IndexingRun.Status.RUNNING,
                phase,
                CREATED,
                Optional.empty(),
                List.of(),
                stagedSnapshot,
                activeBefore,
                activeBefore,
                Optional.of("running before crash"));
    }

    private static IndexerSelection selection() {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "java-indexer",
                "1",
                "java-indexer",
                Set.of(Language.JAVA),
                Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED,
                100,
                List.of());
        return new IndexerSelection(Language.JAVA, descriptor);
    }
}
