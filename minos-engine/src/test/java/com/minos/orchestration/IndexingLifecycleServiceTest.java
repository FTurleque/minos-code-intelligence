package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState.Availability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingLifecycleServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-23T07:15:00Z"), ZoneOffset.UTC);

    @Test
    void promotesOnlyAfterEverySelectedIndexerAndStagingSucceed(@TempDir Path root) throws IOException {
        Path javaArtifact = Files.writeString(root.resolve("java.scip"), "java");
        Path typescriptArtifact = Files.writeString(root.resolve("typescript.scip"), "typescript");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        RecordingStager stager = new RecordingStager("snapshot-new");
        RecordingPromoter promoter = new RecordingPromoter(false);
        IndexingLifecycleService service = service(
                List.of(
                        successfulExecutor("java-indexer", Language.JAVA, javaArtifact),
                        successfulExecutor("typescript-indexer", Language.TYPESCRIPT, typescriptArtifact)
                ), stager, promoter, store);
        UUID projectId = UUID.randomUUID();

        IndexingRun run = service.execute(projectId, root, negotiation(
                selection("java-indexer", Language.JAVA),
                selection("typescript-indexer", Language.TYPESCRIPT)));

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(IndexingRun.Phase.COMPLETED, run.phase());
        assertEquals(2, run.executions().size());
        assertEquals(Optional.of("snapshot-new"), run.stagedSnapshotId());
        assertEquals(Optional.of("snapshot-new"), run.activeSnapshotAfter());
        assertEquals(1, stager.calls.get());
        assertEquals(2, stager.lastRequest.get().artifacts().size());
        assertEquals(1, promoter.calls.get());

        ProjectIndexState state = service.projectState(projectId);
        assertEquals(Availability.READY, state.availability());
        assertEquals(Optional.of("snapshot-new"), state.activeSnapshotId());
        assertEquals(Optional.of(run.id()), state.latestRunId());
    }

    @Test
    void lifecycleLeaseSerializesDifferentServiceInstances(@TempDir Path root) throws Exception {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        RecordingStager stager = new RecordingStager("snapshot-new");
        RecordingPromoter promoter = new RecordingPromoter(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCallerStarted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger activeExecutions = new AtomicInteger();
        AtomicInteger maximumConcurrentExecutions = new AtomicInteger();
        IndexerExecutor blocking = new IndexerExecutor() {
            @Override public String indexerId() { return "java-indexer"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                int invocation = invocations.incrementAndGet();
                int active = activeExecutions.incrementAndGet();
                maximumConcurrentExecutions.accumulateAndGet(active, Math::max);
                try {
                    if (invocation == 1) {
                        firstEntered.countDown();
                        if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test timed out waiting to release first provider");
                        }
                    }
                    return new IndexingArtifact(Language.JAVA, "java-indexer", artifact);
                } finally {
                    activeExecutions.decrementAndGet();
                }
            }
        };
        IndexingLifecycleService first = service(List.of(blocking), stager, promoter, store);
        IndexingLifecycleService second = service(List.of(blocking), stager, promoter, store);
        UUID projectId = UUID.randomUUID();
        IndexerNegotiationResult negotiation = negotiation(selection("java-indexer", Language.JAVA));

        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstRun = pool.submit(() -> first.execute(projectId, root, negotiation));
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
            var secondRun = pool.submit(() -> {
                secondCallerStarted.countDown();
                return second.execute(projectId, root, negotiation);
            });
            assertTrue(secondCallerStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(150L);
            assertEquals(1, invocations.get(), "second lifecycle must not start a provider while the lease is held");
            assertFalse(secondRun.isDone());

            releaseFirst.countDown();
            assertEquals(IndexingRun.Status.SUCCEEDED, firstRun.get(10, TimeUnit.SECONDS).status());
            assertEquals(IndexingRun.Status.SUCCEEDED, secondRun.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(2, invocations.get());
        assertEquals(1, maximumConcurrentExecutions.get());
    }

    @Test
    void oneProviderFailurePreventsStagingAndPromotionForTheWholeProject(@TempDir Path root) throws IOException {
        Path javaArtifact = Files.writeString(root.resolve("java.scip"), "java");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        RecordingStager stager = new RecordingStager("snapshot-should-not-exist");
        RecordingPromoter promoter = new RecordingPromoter(false);
        IndexingLifecycleService service = service(
                List.of(
                        successfulExecutor("java-indexer", Language.JAVA, javaArtifact),
                        failingExecutor("typescript-indexer", "typescript indexing failed")
                ), stager, promoter, store);
        UUID projectId = UUID.randomUUID();

        IndexingRun run = service.execute(projectId, root, negotiation(
                selection("java-indexer", Language.JAVA),
                selection("typescript-indexer", Language.TYPESCRIPT)));

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertEquals(IndexingRun.Phase.PROVIDER_EXECUTION, run.phase());
        assertEquals(1, run.executions().size());
        assertEquals(0, stager.calls.get());
        assertEquals(0, promoter.calls.get());
        assertEquals(Availability.FAILED, service.projectState(projectId).availability());
        assertTrue(service.projectState(projectId).activeSnapshotId().isEmpty());
    }

    @Test
    void failedRefreshKeepsPreviousSnapshotAndMarksProjectStale(@TempDir Path root) throws IOException {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        UUID projectId = UUID.randomUUID();
        store.saveProjectState(new ProjectIndexState(
                projectId,
                Availability.READY,
                Optional.of("snapshot-old"),
                Optional.empty(),
                CLOCK.instant(),
                Optional.of("previous successful snapshot")
        ));
        RecordingStager stager = new RecordingStager("snapshot-new");
        RecordingPromoter promoter = new RecordingPromoter(true);
        IndexingLifecycleService service = service(
                List.of(successfulExecutor("java-indexer", Language.JAVA, artifact)), stager, promoter, store);

        IndexingRun run = service.execute(projectId, root, negotiation(selection("java-indexer", Language.JAVA)));

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertEquals(IndexingRun.Phase.PROMOTION, run.phase());
        assertEquals(Optional.of("snapshot-new"), run.stagedSnapshotId());
        assertEquals(Optional.of("snapshot-old"), run.activeSnapshotBefore());
        assertEquals(Optional.of("snapshot-old"), run.activeSnapshotAfter());
        assertEquals(1, stager.calls.get());
        assertEquals(1, promoter.calls.get());

        ProjectIndexState state = service.projectState(projectId);
        assertEquals(Availability.STALE, state.availability());
        assertEquals(Optional.of("snapshot-old"), state.activeSnapshotId());
    }

    @Test
    void refusesIncompleteNegotiationBeforeStartingARun(@TempDir Path root) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService service = service(List.of(), request -> "unused",
                (projectId, runId, stagedSnapshotId) -> { }, store);
        UUID projectId = UUID.randomUUID();
        IndexerNegotiationResult incomplete = new IndexerNegotiationResult(List.of(), Set.of(Language.JAVA), List.of());

        assertThrows(IllegalArgumentException.class, () -> service.execute(projectId, root, incomplete));
        assertTrue(store.listRuns(projectId).isEmpty());
        assertEquals(Availability.NEVER_INDEXED, service.projectState(projectId).availability());
    }

    private static IndexingLifecycleService service(
            List<IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            InMemoryIndexStateStore store
    ) {
        return new IndexingLifecycleService(executors, stager, promoter, store, CLOCK);
    }

    private static IndexerExecutor successfulExecutor(String id, Language language, Path artifact) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return id; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                return new IndexingArtifact(language, id, artifact);
            }
        };
    }

    private static IndexerExecutor failingExecutor(String id, String message) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return id; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                throw new IllegalStateException(message);
            }
        };
    }

    private static IndexerNegotiationResult negotiation(IndexerSelection... selections) {
        return new IndexerNegotiationResult(List.of(selections), Set.of(), List.of());
    }

    private static IndexerSelection selection(String id, Language language) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                id, "1.0", id, Set.of(language), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerSelection(language, descriptor);
    }

    private static final class RecordingStager implements SnapshotStager {
        private final String snapshotId;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<IndexSnapshotStageRequest> lastRequest = new AtomicReference<>();
        private RecordingStager(String snapshotId) { this.snapshotId = snapshotId; }
        @Override public String stage(IndexSnapshotStageRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            return snapshotId;
        }
    }

    private static final class RecordingPromoter implements SnapshotPromoter {
        private final boolean fail;
        private final AtomicInteger calls = new AtomicInteger();
        private RecordingPromoter(boolean fail) { this.fail = fail; }
        @Override public void promote(UUID projectId, UUID runId, String stagedSnapshotId) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("promotion failed");
        }
    }
}
