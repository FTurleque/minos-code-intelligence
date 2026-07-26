package com.minos.incremental;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalIndexingRealFixtureTest {

    @Test
    void replaysNoChangeAndSourceChangeWithExplicitScipFullFallback(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("typescript-modules");
        copyTree(Path.of("fixtures/typescript/typescript-modules"), project);
        Path artifact = Files.writeString(temp.resolve("typescript.scip"), "fixture-index");

        IndexerRegistry registry = new IndexerRegistry();
        ScipIndexerCatalog.registerQualifiedM1(registry);
        RecordingExecutor executor = new RecordingExecutor(artifact);
        AtomicInteger snapshots = new AtomicInteger();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-" + snapshots.incrementAndGet(),
                (projectId, runId, stagedSnapshotId) -> { },
                new InMemoryIndexStateStore()
        );
        FileProjectFingerprintSnapshotStore fingerprintStore =
                new FileProjectFingerprintSnapshotStore(temp.resolve("fingerprints"));
        IncrementalIndexingCoordinator coordinator = new IncrementalIndexingCoordinator(
                fingerprintStore,
                registry,
                lifecycle
        );
        UUID projectId = UUID.randomUUID();

        IncrementalIndexingResult first = coordinator.refresh(projectId, project, IndexingRequirements.baseline());
        IncrementalIndexingResult unchanged = coordinator.refresh(projectId, project, IndexingRequirements.baseline());

        Path changedSource = project.resolve("packages/app/src/greeting-service.ts");
        Files.writeString(changedSource, Files.readString(changedSource) + System.lineSeparator() + "// M7.4 change");
        IncrementalIndexingResult sourceChanged = coordinator.refresh(
                projectId,
                project,
                IndexingRequirements.baseline()
        );

        assertEquals(IndexingMode.FULL, first.plan().mode());
        assertTrue(first.fingerprintBaselineAdvanced());
        assertEquals(IndexingMode.NONE, unchanged.plan().mode());
        assertTrue(unchanged.run().isEmpty());
        assertEquals(IndexingMode.FULL, sourceChanged.plan().mode());
        assertEquals(List.of("scip-typescript"), sourceChanged.plan().missingIncrementalCapabilityIndexerIds());
        assertTrue(sourceChanged.fingerprintBaselineAdvanced());
        assertEquals(2, executor.calls.get());
        assertEquals(IndexingMode.FULL, executor.lastRequest.get().mode());
        assertEquals(List.of(), executor.lastRequest.get().changedFiles());
        assertFalse(ScipIndexerCatalog.scipTypeScript().capabilities()
                .contains(com.minos.orchestration.IndexerCapability.INCREMENTAL_INDEXING));
        assertEquals("snapshot-2", fingerprintStore.loadActive(projectId).orElseThrow().indexSnapshotId());

        System.out.printf(
                "M7.4 typescript-modules planning: initial=%s, unchanged=%s, source=%s, missing-capability=%s, baseline=%s%n",
                first.plan().mode(),
                unchanged.plan().mode(),
                sourceChanged.plan().mode(),
                sourceChanged.plan().missingIncrementalCapabilityIndexerIds(),
                fingerprintStore.loadActive(projectId).orElseThrow().indexSnapshotId()
        );
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

    private static final class RecordingExecutor implements IndexerExecutor {
        private final Path artifact;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<IndexingExecutionRequest> lastRequest = new AtomicReference<>();

        private RecordingExecutor(Path artifact) {
            this.artifact = artifact;
        }

        @Override
        public String indexerId() {
            return "scip-typescript";
        }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            return new IndexingArtifact(Language.TYPESCRIPT, indexerId(), artifact);
        }
    }
}
