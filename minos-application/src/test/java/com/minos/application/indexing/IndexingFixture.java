package com.minos.application.indexing;

import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.discovery.SourceRootDetector;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IncrementalIndexingPlanner;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProjectInvalidationService;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** In-memory ports for the indexing use case: no class of a surface, no adapter. */
final class IndexingFixture {
    static final String INDEXER = "java-indexer";

    final Path root;
    final RegisteredProject project;
    final InMemoryIndexStateStore stateStore = new InMemoryIndexStateStore();
    final SnapshotStore snapshots = new SnapshotStore();
    ProjectFingerprintSnapshotStore fingerprints = new FingerprintStore();
    final RecordingExecutor executor;
    final AtomicInteger compactions = new AtomicInteger();
    final AtomicInteger semanticRefreshes = new AtomicInteger();
    final AtomicInteger snapshotIds = new AtomicInteger();
    ProviderRuntimeStatus.State runtimeState = ProviderRuntimeStatus.State.READY;
    IOException retentionFailure;
    RuntimeException promotionFailure;
    boolean qualifiedProvider = true;
    List<SourceRootDetector> extraDetectors = List.of();

    IndexingFixture(Path temp) throws IOException {
        this.root = javaProject(temp.resolve("project"));
        Path artifact = Files.writeString(temp.resolve("java.scip"), "index");
        this.executor = new RecordingExecutor(artifact);
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        this.project = new RegisteredProject(UUID.randomUUID(), root, "demo", Optional.empty(), now, now);
    }

    static Path javaProject(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");
        return root;
    }

    String id() {
        return project.id().toString();
    }

    ProjectIndexingUseCase useCase() {
        return useCase(UnaryOperator.identity());
    }

    ProjectIndexingUseCase useCase(UnaryOperator<IndexerExecutor> decorator) {
        List<SourceRootDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.sourceRootDetectors());
        detectors.addAll(extraDetectors);
        ProjectDiscoveryService discovery = new ProjectDiscoveryService(
                DefaultDiscoveryPlugins.projectDetectors(),
                DefaultDiscoveryPlugins.buildSystemDetectors(),
                detectors,
                DefaultDiscoveryPlugins.languageDetectors());
        StorageRetentionService retention = (projectId, policy) -> {
            compactions.incrementAndGet();
            if (retentionFailure != null) throw retentionFailure;
            return new StorageRetentionService.RetentionResult(0, 0, 0);
        };
        return new ProjectIndexingUseCase(new ProjectIndexingUseCase.Dependencies(
                new Registry(project),
                snapshots,
                stateStore,
                fingerprints,
                new Runtime(),
                request -> "snapshot-" + snapshotIds.incrementAndGet(),
                (projectId, runId, stagedSnapshotId) -> {
                    snapshots.active.put(projectId, stagedSnapshotId);
                    if (promotionFailure != null) throw promotionFailure;
                },
                ResumableRunMarkers.none(),
                discovery,
                new ProjectFingerprintService(),
                new ProjectInvalidationService(),
                new IncrementalIndexingPlanner(),
                override -> registry(),
                retention,
                projectId -> {
                    semanticRefreshes.incrementAndGet();
                    return null;
                }), decorator);
    }

    private IndexerRegistry registry() {
        IndexerRegistry registry = new IndexerRegistry();
        if (!qualifiedProvider) return registry;
        registry.register(new IndexerDescriptor(
                INDEXER, "1.0", INDEXER,
                Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES,
                        IndexerCapability.INCREMENTAL_INDEXING),
                IndexerQualification.QUALIFIED, 100, List.of()));
        return registry;
    }

    final class Runtime implements ProviderRuntimeManager {
        private ProviderRuntimeStatus status() {
            return new ProviderRuntimeStatus(INDEXER, "1.0", runtimeState, Optional.empty(),
                    runtimeState == ProviderRuntimeStatus.State.READY ? List.of() : List.of("not installed"), true);
        }

        @Override public List<ProviderRuntimeStatus> list() { return List.of(status()); }
        @Override public ProviderRuntimeStatus inspect(String providerId) { return status(); }
        @Override public ProviderRuntimeStatus install(String providerId) { return status(); }
        @Override public IndexerExecutor executor(String providerId) { return executor; }
    }

    static final class RecordingExecutor implements IndexerExecutor {
        private final Path artifact;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<IndexingExecutionRequest> lastRequest = new AtomicReference<>();
        volatile RuntimeException failure;
        volatile Runnable duringRun = () -> { };

        RecordingExecutor(Path artifact) {
            this.artifact = artifact;
        }

        @Override public String indexerId() { return INDEXER; }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            duringRun.run();
            if (failure != null) throw failure;
            return new IndexingArtifact(Language.JAVA, INDEXER, artifact);
        }
    }

    /** A registry that knows one project; every other operation is out of the use case. */
    static final class Registry implements ProjectRegistry {
        private final RegisteredProject project;

        Registry(RegisteredProject project) {
            this.project = project;
        }

        @Override public RegisteredProject registerProject(Path rootPath, String displayName) { throw unused(); }
        @Override public RegisteredWorkspace createWorkspace(String name) { throw unused(); }
        @Override public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) { throw unused(); }
        @Override public RegisteredProject removeProjectFromWorkspace(UUID projectId) { throw unused(); }
        @Override public Optional<RegisteredProject> findProject(UUID projectId) {
            return project.id().equals(projectId) ? Optional.of(project) : Optional.empty();
        }
        @Override public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) { return Optional.empty(); }
        @Override public List<RegisteredProject> listProjects() { return List.of(project); }
        @Override public List<RegisteredWorkspace> listWorkspaces() { return List.of(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by the indexing use case");
        }
    }

    /** Only the identity of the active snapshot matters to the orchestration. */
    static final class SnapshotStore implements CodeKnowledgeSnapshotStore {
        final Map<UUID, String> active = new ConcurrentHashMap<>();

        @Override public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException();
        }
        @Override public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                                       Collection<SymbolOccurrence> occurrences,
                                                       Collection<Relationship> relationships) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<SymbolSnapshot> loadActive(UUID projectId) { return Optional.empty(); }
        @Override public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return Optional.ofNullable(active.get(projectId))
                    .map(id -> new CodeKnowledgeSnapshot(projectId, id, List.of(), List.of(), List.of()));
        }
        @Override public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) { return Optional.empty(); }
        @Override public Optional<String> activeSnapshotId(UUID projectId) {
            return Optional.ofNullable(active.get(projectId));
        }
    }

    static class FingerprintStore implements ProjectFingerprintSnapshotStore {
        private final Map<String, ProjectFingerprintSnapshot> snapshots = new HashMap<>();
        private final Map<UUID, String> active = new HashMap<>();
        final List<String> promoted = new ArrayList<>();

        @Override
        public synchronized ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId,
                                                               ProjectFingerprint fingerprint) throws IOException {
            ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
            snapshots.put(projectId + "/" + indexSnapshotId, snapshot);
            return snapshot;
        }

        @Override
        public void promote(UUID projectId, String indexSnapshotId) throws IOException {
            synchronized (this) {
                active.put(projectId, indexSnapshotId);
                promoted.add(indexSnapshotId);
            }
        }

        @Override
        public synchronized Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) {
            return Optional.ofNullable(snapshots.get(projectId + "/" + indexSnapshotId));
        }

        @Override
        public synchronized Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
            return Optional.ofNullable(active.get(projectId)).flatMap(id -> load(projectId, id));
        }

        @Override
        public synchronized List<String> listIndexSnapshotIds(UUID projectId) {
            return snapshots.keySet().stream()
                    .filter(key -> key.startsWith(projectId + "/"))
                    .map(key -> key.substring(key.indexOf('/') + 1))
                    .toList();
        }
    }
}
