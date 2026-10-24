package com.minos.app.incremental;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.application.MinosApplication;
import com.minos.application.indexing.ProjectIndexingUseCase;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real TypeScript fixture through the single indexing orchestration of the product (AUD-ARC-02): an unchanged
 * project answers NO_CHANGES, a source change falls back to a complete run because scip-typescript does not declare
 * incremental indexing, and the fingerprint baseline follows the promoted snapshot.
 */
class IncrementalIndexingRealFixtureTest {

    @Test
    void replaysNoChangeAndSourceChangeWithExplicitScipFullFallback(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("typescript-modules");
        copyTree(Path.of("fixtures/typescript/typescript-modules"), project);
        Path artifact = Files.writeString(temp.resolve("typescript.scip"), "fixture-index");
        RecordingExecutor executor = new RecordingExecutor(artifact);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<MinosApplication> self = new AtomicReference<>();

        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .indexerDescriptors(ScipIndexerCatalog.javaTypeScriptDescriptors())
                .providerRuntimeManager(new ReadyRuntime(executor))
                .snapshotLifecycle(
                        request -> "snapshot-" + snapshots.incrementAndGet(),
                        (projectId, runId, stagedSnapshotId) -> {
                            try {
                                self.get().snapshotStore().publish(projectId, stagedSnapshotId, List.of(), List.of(), List.of());
                            } catch (java.io.IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        })
                .build()) {
            self.set(application);
            RegisteredProject registered = application.projectRegistry().registerProject(project, "typescript-modules");
            ProjectIndexingUseCase indexing = ProjectIndexingUseCase.of(application, UnaryOperator.identity());
            String id = registered.id().toString();

            var first = indexing.execute(id, null, false, IndexingResumePolicy.RESUME);
            var unchanged = indexing.execute(id, null, false, IndexingResumePolicy.RESUME);
            Path changedSource = project.resolve("packages/app/src/greeting-service.ts");
            Files.writeString(changedSource, Files.readString(changedSource) + System.lineSeparator() + "// M7.4 change");
            var sourceChanged = indexing.execute(id, null, false, IndexingResumePolicy.RESUME);

            assertEquals(IndexingMode.FULL, first.preparation().effectiveMode());
            assertTrue(first.fingerprintPromoted());
            assertEquals(IndexingMode.NONE, unchanged.preparation().effectiveMode());
            assertTrue(unchanged.run().isEmpty());
            assertEquals(IndexingMode.FULL, sourceChanged.preparation().effectiveMode());
            assertEquals(List.of("scip-typescript"),
                    sourceChanged.preparation().plan().missingIncrementalCapabilityIndexerIds());
            assertTrue(sourceChanged.fingerprintPromoted());
            // The product path executes once per discovered scope; what the coordinator counted was runs.
            assertEquals(2, executor.runIds.size(), "two provider runs: the initial one and the source change");
            assertEquals(IndexingMode.FULL, executor.lastRequest.get().mode());
            assertEquals(List.of(), executor.lastRequest.get().changedFiles());
            assertFalse(ScipIndexerCatalog.scipTypeScript().capabilities()
                    .contains(IndexerCapability.INCREMENTAL_INDEXING));
            assertEquals("snapshot-2",
                    application.fingerprintStore().loadActive(registered.id()).orElseThrow().indexSnapshotId());
        }
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private record ReadyRuntime(RecordingExecutor executor) implements ProviderRuntimeManager {
        private static ProviderRuntimeStatus ready(String providerId) {
            return new ProviderRuntimeStatus(providerId, "fixture", ProviderRuntimeStatus.State.READY,
                    Optional.empty(), List.of(), true);
        }

        @Override public List<ProviderRuntimeStatus> list() { return List.of(ready("scip-typescript")); }
        @Override public ProviderRuntimeStatus inspect(String providerId) { return ready(providerId); }
        @Override public ProviderRuntimeStatus install(String providerId) { return ready(providerId); }
        @Override public IndexerExecutor executor(String providerId) { return executor; }
    }

    private static final class RecordingExecutor implements IndexerExecutor {
        private final Path artifact;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<IndexingExecutionRequest> lastRequest = new AtomicReference<>();
        private final java.util.Set<java.util.UUID> runIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private RecordingExecutor(Path artifact) {
            this.artifact = artifact;
        }

        @Override
        public String indexerId() {
            return "scip-typescript";
        }

        /** The fixture artifact is a file of the test, not the output of a provider run. */
        @Override
        public boolean artifactsLiveInRunDirectory() {
            return false;
        }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            runIds.add(request.runId());
            // The product path runs one execution per discovered scope: the artifact answers for that scope.
            return new IndexingArtifact(Language.TYPESCRIPT, indexerId(), artifact, request.projectRelativeRoot());
        }
    }
}
