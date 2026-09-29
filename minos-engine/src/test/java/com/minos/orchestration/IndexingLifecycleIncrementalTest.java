package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectChangeSet;
import com.minos.incremental.ProjectInvalidationAssessment;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.incremental.ProjectInvalidationScope;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingLifecycleIncrementalTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private final IncrementalIndexingPlanner planner = new IncrementalIndexingPlanner();

    @Test
    void incrementalPlanForwardsModeAndChangedFiles(@TempDir Path root) throws Exception {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        RecordingExecutor executor = new RecordingExecutor("java-indexer", Language.JAVA, artifact);
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore store = stateWithSnapshot(projectId, "snapshot-old");
        IndexingLifecycleService service = service(executor, store);
        IndexerNegotiationResult negotiation = negotiation(selection("java-indexer", Language.JAVA, true));
        IncrementalIndexingPlan plan = planner.plan(partial(projectId), negotiation);

        Optional<IndexingRun> run = service.executePlanned(projectId, root, negotiation, plan);

        assertTrue(run.isPresent());
        assertEquals(IndexingRun.Status.SUCCEEDED, run.orElseThrow().status());
        assertEquals(IndexingMode.INCREMENTAL, executor.lastRequest.get().mode());
        assertEquals(List.of("src/App.java"), executor.lastRequest.get().changedFiles());
        assertEquals(1, executor.calls.get());
    }

    @Test
    void fullFallbackNeverLeaksPartialFileScope(@TempDir Path root) throws Exception {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        RecordingExecutor executor = new RecordingExecutor("java-indexer", Language.JAVA, artifact);
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore store = stateWithSnapshot(projectId, "snapshot-old");
        IndexingLifecycleService service = service(executor, store);
        IndexerNegotiationResult negotiation = negotiation(selection("java-indexer", Language.JAVA, false));
        IncrementalIndexingPlan plan = planner.plan(partial(projectId), negotiation);

        Optional<IndexingRun> run = service.executePlanned(projectId, root, negotiation, plan);

        assertTrue(run.isPresent());
        assertEquals(IndexingMode.FULL, plan.mode());
        assertEquals(IndexingMode.FULL, executor.lastRequest.get().mode());
        assertEquals(List.of(), executor.lastRequest.get().changedFiles());
    }

    @Test
    void stalePlanIsRejectedAfterAnotherLifecycleAdvancesTheSnapshot(@TempDir Path root) throws Exception {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        RecordingExecutor executor = new RecordingExecutor("java-indexer", Language.JAVA, artifact);
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore store = stateWithSnapshot(projectId, "snapshot-newer");
        IndexingLifecycleService service = service(executor, store);
        IndexerNegotiationResult negotiation = negotiation(selection("java-indexer", Language.JAVA, true));
        IncrementalIndexingPlan stale = planner.plan(partial(projectId), negotiation);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.executePlanned(projectId, root, negotiation, stale));

        assertTrue(failure.getMessage().contains("indexing plan is stale"));
        assertEquals(0, executor.calls.get());
    }

    @Test
    void noChangePlanCreatesNoRunAndDoesNotCallExecutor(@TempDir Path root) throws Exception {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        RecordingExecutor executor = new RecordingExecutor("java-indexer", Language.JAVA, artifact);
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore store = stateWithSnapshot(projectId, "snapshot-old");
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-new",
                (id, runId, stagedSnapshotId) -> { },
                store
        );
        IndexerNegotiationResult negotiation = negotiation(selection("java-indexer", Language.JAVA, false));
        IncrementalIndexingPlan plan = planner.plan(unchanged(projectId), negotiation);

        Optional<IndexingRun> run = service.executePlanned(projectId, root, negotiation, plan);

        assertTrue(run.isEmpty());
        assertEquals(0, executor.calls.get());
        assertTrue(store.listRuns(projectId).isEmpty());
    }

    @Test
    void lifecycleRejectsForgedIncrementalPlanWhenNegotiatedIndexerLacksCapability(@TempDir Path root) {
        IndexerNegotiationResult negotiation = negotiation(selection("java-indexer", Language.JAVA, false));
        UUID projectId = UUID.randomUUID();
        ProjectInvalidationAssessment assessment = partial(projectId);
        IncrementalIndexingPlan forged = new IncrementalIndexingPlan(
                projectId,
                IndexingMode.INCREMENTAL,
                assessment,
                List.of("src/App.java"),
                List.of("java-indexer"),
                List.of("java-indexer"),
                List.of(),
                List.of(com.minos.incremental.IncrementalIndexingPlanReason.ALL_INDEXERS_SUPPORT_INCREMENTAL)
        );
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(),
                request -> "unused",
                (id, runId, staged) -> { },
                new InMemoryIndexStateStore()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.executePlanned(projectId, root, negotiation, forged)
        );
    }

    private static IndexingLifecycleService service(RecordingExecutor executor, InMemoryIndexStateStore store) {
        return new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-new",
                (projectId, runId, stagedSnapshotId) -> { },
                store
        );
    }

    private static InMemoryIndexStateStore stateWithSnapshot(UUID projectId, String snapshotId) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(snapshotId),
                Optional.empty(),
                Instant.EPOCH,
                Optional.of("test baseline")));
        return store;
    }

    private static ProjectInvalidationAssessment partial(UUID projectId) {
        ProjectChangeSet changeSet = new ProjectChangeSet(
                A, B, C, C, true, false,
                List.of(), List.of("src/App.java"), List.of(), List.of()
        );
        return new ProjectInvalidationAssessment(
                projectId,
                Optional.of("snapshot-old"),
                Optional.of("snapshot-old"),
                ProjectInvalidationScope.PARTIAL_CANDIDATE,
                List.of(ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED),
                Optional.of(changeSet),
                List.of("src/App.java"),
                List.of(),
                List.of()
        );
    }

    private static ProjectInvalidationAssessment unchanged(UUID projectId) {
        ProjectChangeSet changeSet = new ProjectChangeSet(
                A, A, C, C, false, false,
                List.of(), List.of(), List.of(), List.of("src/App.java")
        );
        return new ProjectInvalidationAssessment(
                projectId,
                Optional.of("snapshot-old"),
                Optional.of("snapshot-old"),
                ProjectInvalidationScope.NONE,
                List.of(),
                Optional.of(changeSet),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static IndexerSelection selection(String id, Language language, boolean incremental) {
        EnumSet<IndexerCapability> capabilities = EnumSet.of(
                IndexerCapability.SYMBOLS,
                IndexerCapability.REFERENCES
        );
        if (incremental) capabilities.add(IndexerCapability.INCREMENTAL_INDEXING);
        return new IndexerSelection(
                language,
                new IndexerDescriptor(
                        id,
                        "1.0",
                        id,
                        Set.of(language),
                        Set.of(),
                        capabilities,
                        IndexerQualification.QUALIFIED,
                        100,
                        List.of()
                )
        );
    }

    private static IndexerNegotiationResult negotiation(IndexerSelection selection) {
        return new IndexerNegotiationResult(List.of(selection), Set.of(), List.of());
    }

    private static final class RecordingExecutor implements IndexerExecutor {
        private final String id;
        private final Language language;
        private final Path artifact;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<IndexingExecutionRequest> lastRequest = new AtomicReference<>();

        private RecordingExecutor(String id, Language language, Path artifact) {
            this.id = id;
            this.language = language;
            this.artifact = artifact;
        }

        @Override public String indexerId() { return id; }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            return new IndexingArtifact(language, id, artifact);
        }
    }
}
