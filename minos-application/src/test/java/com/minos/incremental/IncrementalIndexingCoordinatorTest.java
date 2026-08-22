package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalIndexingCoordinatorTest {

    @Test
    void qualifiedProviderReceivesIncrementalScopeAndAdvancesBaseline(@TempDir Path temp) throws Exception {
        Path project = javaProject(temp.resolve("project"));
        Path artifact = Files.writeString(temp.resolve("java.scip"), "index");
        RecordingExecutor executor = new RecordingExecutor(artifact, false);
        FileProjectFingerprintSnapshotStore fingerprintStore =
                new FileProjectFingerprintSnapshotStore(temp.resolve("fingerprints"));
        IncrementalIndexingCoordinator coordinator = coordinator(
                fingerprintStore,
                executor,
                registry(true)
        );
        UUID projectId = UUID.randomUUID();

        IncrementalIndexingResult initial = coordinator.refresh(projectId, project, IndexingRequirements.baseline());
        Files.writeString(project.resolve("src/main/java/App.java"), "class App { int changed = 1; }");
        IncrementalIndexingResult changed = coordinator.refresh(projectId, project, IndexingRequirements.baseline());
        IncrementalIndexingResult unchanged = coordinator.refresh(projectId, project, IndexingRequirements.baseline());

        assertEquals(IndexingMode.FULL, initial.plan().mode());
        assertEquals(IndexingMode.INCREMENTAL, changed.plan().mode());
        assertEquals(List.of("src/main/java/App.java"), changed.plan().changedFiles());
        assertEquals(IndexingMode.INCREMENTAL, executor.lastRequest.get().mode());
        assertEquals(List.of("src/main/java/App.java"), executor.lastRequest.get().changedFiles());
        assertTrue(changed.fingerprintBaselineAdvanced());
        assertEquals(IndexingMode.NONE, unchanged.plan().mode());
        assertEquals(2, executor.calls.get());
        assertEquals("snapshot-2", fingerprintStore.loadActive(projectId).orElseThrow().indexSnapshotId());
    }

    @Test
    void projectLeaseRemainsHeldThroughFingerprintPromotion(@TempDir Path temp) throws Exception {
        Path project = javaProject(temp.resolve("serialized-project"));
        Path artifact = Files.writeString(temp.resolve("serialized-java.scip"), "index");
        RecordingExecutor executor = new RecordingExecutor(artifact, false);
        InMemoryIndexStateStore stateStore = new InMemoryIndexStateStore();
        AtomicInteger snapshots = new AtomicInteger();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-" + snapshots.incrementAndGet(),
                (projectId, runId, stagedSnapshotId) -> { },
                stateStore);
        BlockingPromoteFingerprintStore fingerprintStore = new BlockingPromoteFingerprintStore(
                new FileProjectFingerprintSnapshotStore(temp.resolve("serialized-fingerprints")));
        IncrementalIndexingCoordinator coordinator = new IncrementalIndexingCoordinator(
                fingerprintStore, registry(true), lifecycle);
        UUID projectId = UUID.randomUUID();
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> coordinator.refresh(projectId, project, IndexingRequirements.baseline()));
            assertTrue(fingerprintStore.promoteEntered.await(10, TimeUnit.SECONDS));
            assertEquals(1, executor.calls.get());

            var second = pool.submit(() -> {
                secondStarted.countDown();
                return coordinator.refresh(projectId, project, IndexingRequirements.baseline());
            });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(150L);
            assertFalse(second.isDone(),
                    "a second refresh must remain behind the project lease while baseline promotion is in progress");
            assertEquals(1, executor.calls.get(), "no second provider run may begin before baseline promotion completes");

            fingerprintStore.releasePromote.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS).fingerprintBaselineAdvanced());
            IncrementalIndexingResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(IndexingMode.NONE, secondResult.plan().mode());
        }

        assertEquals("snapshot-1", fingerprintStore.loadActive(projectId).orElseThrow().indexSnapshotId());
        assertEquals(1, executor.calls.get());
    }

    @Test
    void workspaceMutationDuringRunLeavesBaselineBehindAndForcesHealingFullRefresh(@TempDir Path temp)
            throws Exception {
        Path project = javaProject(temp.resolve("project"));
        Path artifact = Files.writeString(temp.resolve("java.scip"), "index");
        RecordingExecutor executor = new RecordingExecutor(artifact, true);
        FileProjectFingerprintSnapshotStore fingerprintStore =
                new FileProjectFingerprintSnapshotStore(temp.resolve("fingerprints"));
        IncrementalIndexingCoordinator coordinator = coordinator(
                fingerprintStore,
                executor,
                registry(true)
        );
        UUID projectId = UUID.randomUUID();

        executor.projectRootToMutate.set(project);
        IncrementalIndexingResult unstable = coordinator.refresh(projectId, project, IndexingRequirements.baseline());

        assertEquals(IndexingMode.FULL, unstable.plan().mode());
        assertFalse(unstable.workspaceStableDuringRun());
        assertFalse(unstable.fingerprintBaselineAdvanced());
        assertTrue(fingerprintStore.loadActive(projectId).isEmpty());
        assertTrue(unstable.diagnostic().orElseThrow().contains("workspace changed during indexing"));

        IncrementalIndexingResult healing = coordinator.refresh(projectId, project, IndexingRequirements.baseline());

        assertEquals(IndexingMode.FULL, healing.plan().mode());
        assertEquals(
                List.of(ProjectInvalidationReason.MISSING_FINGERPRINT_BASELINE),
                healing.plan().invalidation().reasons()
        );
        assertTrue(healing.workspaceStableDuringRun());
        assertTrue(healing.fingerprintBaselineAdvanced());
        assertEquals("snapshot-2", fingerprintStore.loadActive(projectId).orElseThrow().indexSnapshotId());
    }

    private static IncrementalIndexingCoordinator coordinator(
            ProjectFingerprintSnapshotStore fingerprintStore,
            RecordingExecutor executor,
            IndexerRegistry registry
    ) {
        AtomicInteger snapshots = new AtomicInteger();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-" + snapshots.incrementAndGet(),
                (projectId, runId, stagedSnapshotId) -> { },
                new InMemoryIndexStateStore()
        );
        return new IncrementalIndexingCoordinator(fingerprintStore, registry, lifecycle);
    }

    private static IndexerRegistry registry(boolean incremental) {
        EnumSet<IndexerCapability> capabilities = EnumSet.of(
                IndexerCapability.SYMBOLS,
                IndexerCapability.REFERENCES
        );
        if (incremental) capabilities.add(IndexerCapability.INCREMENTAL_INDEXING);
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(new IndexerDescriptor(
                "java-indexer",
                "1.0",
                "java-indexer",
                Set.of(Language.JAVA),
                Set.of(),
                capabilities,
                IndexerQualification.QUALIFIED,
                100,
                List.of()
        ));
        return registry;
    }

    private static Path javaProject(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");
        return root;
    }

    private static final class RecordingExecutor implements IndexerExecutor {
        private final Path artifact;
        private final boolean mutateFirstRun;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<IndexingExecutionRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<Path> projectRootToMutate = new AtomicReference<>();

        private RecordingExecutor(Path artifact, boolean mutateFirstRun) {
            this.artifact = artifact;
            this.mutateFirstRun = mutateFirstRun;
        }

        @Override
        public String indexerId() {
            return "java-indexer";
        }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
            int call = calls.incrementAndGet();
            lastRequest.set(request);
            if (mutateFirstRun && call == 1) {
                Path root = projectRootToMutate.get();
                Files.writeString(root.resolve("src/main/java/App.java"), "class App { int concurrent = 1; }");
            }
            return new IndexingArtifact(Language.JAVA, indexerId(), artifact);
        }
    }

    private static final class BlockingPromoteFingerprintStore implements ProjectFingerprintSnapshotStore {
        private final ProjectFingerprintSnapshotStore delegate;
        private final CountDownLatch promoteEntered = new CountDownLatch(1);
        private final CountDownLatch releasePromote = new CountDownLatch(1);

        private BlockingPromoteFingerprintStore(ProjectFingerprintSnapshotStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint)
                throws IOException {
            return delegate.publish(projectId, indexSnapshotId, fingerprint);
        }

        @Override
        public void promote(UUID projectId, String indexSnapshotId) throws IOException {
            promoteEntered.countDown();
            try {
                if (!releasePromote.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test timed out while blocking fingerprint promotion");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("fingerprint promotion test was interrupted", interrupted);
            }
            delegate.promote(projectId, indexSnapshotId);
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) throws IOException {
            return delegate.load(projectId, indexSnapshotId);
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
            return delegate.loadActive(projectId);
        }

        @Override
        public List<String> listIndexSnapshotIds(UUID projectId) throws IOException {
            return delegate.listIndexSnapshotIds(projectId);
        }
    }
}
