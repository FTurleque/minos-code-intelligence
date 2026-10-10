package com.minos.orchestration;

import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.discovery.SourceRootDetector;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D06 : la découverte précédait l'empreinte de référence. Une structure créée entre les deux (ici un
 * module qui apparaît pendant la découverte) figurait dans l'empreinte {@code before} sans avoir été découverte ni
 * indexée, puis dans {@code after} : {@code before.equals(after)} et la baseline était promue pour un snapshot qui
 * ne la couvre pas (ADR 0014 : les empreintes décrivent ce qui a été indexé). L'empreinte est capturée AVANT.
 */
class IncrementalIndexingDiscoveryOrderTest {

    @Test
    void aStructureCreatedDuringDiscoveryPreventsThePromotionOfTheBaseline(@TempDir Path temp) throws Exception {
        Path project = javaProject(temp.resolve("project"));
        Fixture fixture = fixture(temp, new SourceRootCreatingAModuleAtDiscovery(project));

        IncrementalIndexingResult result = fixture.coordinator().refresh(
                UUID.randomUUID(), project, IndexingRequirements.baseline());

        assertTrue(Files.isRegularFile(project.resolve("late/pom.xml")), "the module did appear during discovery");
        assertEquals(1, fixture.executor().calls.get(), "the run itself succeeded");
        assertFalse(result.workspaceStableDuringRun(),
                "a structure created after the reference fingerprint is a change during the run");
        assertFalse(result.fingerprintBaselineAdvanced(),
                "the baseline of a snapshot that never covered the new module must not be promoted");
        assertTrue(fixture.store().promoted.isEmpty());
    }

    @Test
    void aStableTreeStillPromotesTheBaseline(@TempDir Path temp) throws Exception {
        Path project = javaProject(temp.resolve("project"));
        Fixture fixture = fixture(temp, (projectRoot, moduleRoot, ignorePolicy) -> List.of());

        IncrementalIndexingResult result = fixture.coordinator().refresh(
                UUID.randomUUID(), project, IndexingRequirements.baseline());

        assertTrue(result.workspaceStableDuringRun());
        assertTrue(result.fingerprintBaselineAdvanced());
        assertEquals(1, fixture.store().promoted.size());
    }

    private static Fixture fixture(Path temp, SourceRootDetector extraDetector) throws IOException {
        List<SourceRootDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.sourceRootDetectors());
        detectors.add(extraDetector);
        ProjectDiscoveryService discovery = new ProjectDiscoveryService(
                DefaultDiscoveryPlugins.projectDetectors(),
                DefaultDiscoveryPlugins.buildSystemDetectors(),
                detectors,
                DefaultDiscoveryPlugins.languageDetectors());
        Path artifact = Files.writeString(temp.resolve("java.scip"), "index");
        RecordingExecutor executor = new RecordingExecutor(artifact);
        AtomicInteger snapshots = new AtomicInteger();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-" + snapshots.incrementAndGet(),
                (projectId, runId, stagedSnapshotId) -> { },
                new InMemoryIndexStateStore());
        InMemoryFingerprintStore store = new InMemoryFingerprintStore();
        IncrementalIndexingCoordinator coordinator = new IncrementalIndexingCoordinator(
                discovery,
                new ProjectFingerprintService(),
                store,
                new ProjectInvalidationService(),
                new IncrementalIndexingPlanner(),
                registry(),
                lifecycle);
        return new Fixture(coordinator, executor, store);
    }

    private static IndexerRegistry registry() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(new IndexerDescriptor(
                "java-indexer", "1.0", "java-indexer",
                Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES,
                        IndexerCapability.INCREMENTAL_INDEXING),
                IndexerQualification.QUALIFIED, 100, List.of()));
        return registry;
    }

    private static Path javaProject(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");
        return root;
    }

    private record Fixture(IncrementalIndexingCoordinator coordinator, RecordingExecutor executor,
                           InMemoryFingerprintStore store) {
    }

    /** Appelé par la découverte une fois la marche des modules finie : un module apparaît à ce moment précis. */
    private static final class SourceRootCreatingAModuleAtDiscovery implements SourceRootDetector {
        private final Path project;
        private boolean created;

        private SourceRootCreatingAModuleAtDiscovery(Path project) {
            this.project = project;
        }

        @Override
        public List<com.minos.discovery.ProjectDiscovery.SourceRoot> detect(
                Path projectRoot, Path moduleRoot, com.minos.discovery.ProjectIgnorePolicy ignorePolicy)
                throws IOException {
            if (!created) {
                created = true;
                Files.createDirectories(project.resolve("late"));
                Files.writeString(project.resolve("late/pom.xml"), "<project/>");
            }
            return List.of();
        }
    }

    private static final class RecordingExecutor implements IndexerExecutor {
        private final Path artifact;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingExecutor(Path artifact) {
            this.artifact = artifact;
        }

        @Override
        public String indexerId() {
            return "java-indexer";
        }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            calls.incrementAndGet();
            return new IndexingArtifact(Language.JAVA, indexerId(), artifact);
        }
    }

    private static final class InMemoryFingerprintStore implements ProjectFingerprintSnapshotStore {
        private final Map<String, ProjectFingerprintSnapshot> snapshots = new HashMap<>();
        private final Map<UUID, String> active = new HashMap<>();
        private final List<String> promoted = new ArrayList<>();

        @Override
        public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint) {
            ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
            snapshots.put(projectId + "/" + indexSnapshotId, snapshot);
            return snapshot;
        }

        @Override
        public void promote(UUID projectId, String indexSnapshotId) {
            active.put(projectId, indexSnapshotId);
            promoted.add(indexSnapshotId);
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) {
            return Optional.ofNullable(snapshots.get(projectId + "/" + indexSnapshotId));
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) {
            return Optional.ofNullable(active.get(projectId)).flatMap(id -> load(projectId, id));
        }

        @Override
        public List<String> listIndexSnapshotIds(UUID projectId) {
            return snapshots.keySet().stream()
                    .filter(key -> key.startsWith(projectId + "/"))
                    .map(key -> key.substring(key.indexOf('/') + 1))
                    .toList();
        }
    }
}
