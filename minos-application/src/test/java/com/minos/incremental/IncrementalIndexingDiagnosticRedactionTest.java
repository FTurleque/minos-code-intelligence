package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IncrementalIndexingDiagnosticRedactionTest {

    @Test
    void publicResultRedactsAbsolutePathFromFingerprintFailure(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/main/java/App.java"), "class App {}");
        Path artifact = Files.writeString(temp.resolve("java.scip"), "index");

        FileProjectFingerprintSnapshotStore delegate =
                new FileProjectFingerprintSnapshotStore(temp.resolve("fingerprints"));
        ProjectFingerprintSnapshotStore failingLoad = new ProjectFingerprintSnapshotStore() {
            @Override
            public ProjectFingerprintSnapshot publish(
                    UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint) throws IOException {
                return delegate.publish(projectId, indexSnapshotId, fingerprint);
            }

            @Override
            public void promote(UUID projectId, String indexSnapshotId) throws IOException {
                delegate.promote(projectId, indexSnapshotId);
            }

            @Override
            public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) throws IOException {
                return delegate.load(projectId, indexSnapshotId);
            }

            @Override
            public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
                throw new IOException("active fingerprint missing at C:\\Users\\fabrice\\.minos\\fingerprints\\active.pointer");
            }

            @Override
            public List<String> listIndexSnapshotIds(UUID projectId) throws IOException {
                return delegate.listIndexSnapshotIds(projectId);
            }
        };

        var executor = new com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor() {
            @Override
            public String indexerId() {
                return "java-indexer";
            }

            @Override
            public IndexingArtifact execute(com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request) {
                return new IndexingArtifact(Language.JAVA, indexerId(), artifact);
            }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-1",
                (projectId, runId, stagedSnapshotId) -> { },
                new InMemoryIndexStateStore());
        IncrementalIndexingCoordinator coordinator = new IncrementalIndexingCoordinator(
                failingLoad,
                registry(),
                lifecycle);

        IncrementalIndexingResult result = coordinator.refresh(
                UUID.randomUUID(), project, IndexingRequirements.baseline());

        assertEquals(Optional.of("internal diagnostic redacted"), result.diagnostic());
        assertFalse(result.diagnostic().orElseThrow().contains("fabrice"));
        assertFalse(result.diagnostic().orElseThrow().contains(".minos"));
    }

    private static IndexerRegistry registry() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(new IndexerDescriptor(
                "java-indexer",
                "1.0",
                "java-indexer",
                Set.of(Language.JAVA),
                Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED,
                100,
                List.of()));
        return registry;
    }
}
