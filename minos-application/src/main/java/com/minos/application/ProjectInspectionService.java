package com.minos.application;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.io.BoundedProperties;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared read-only project/index view used by transport adapters. */
public final class ProjectInspectionService {

    private static final long MAX_HISTORY_PROPERTIES_BYTES = 64L * 1024L;

    private final ProjectRegistry registry;
    private final ProjectResolver projectResolver;
    private final IndexStateStore stateStore;
    private final ProjectDiscoveryService discoveryService;
    private final Path historyDirectory;
    private final Map<String, IndexerDescriptor> knownProviders;
    private final ProjectIndexStateReconciler reconciler;

    public ProjectInspectionService(
            Path home,
            ProjectRegistry registry,
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore stateStore,
            ProjectDiscoveryService discoveryService,
            List<IndexerDescriptor> indexerDescriptors
    ) {
        this(home, registry, new ProjectResolver(registry), snapshotStore, stateStore, discoveryService, indexerDescriptors);
    }

    public ProjectInspectionService(
            Path home,
            ProjectRegistry registry,
            ProjectResolver projectResolver,
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore stateStore,
            ProjectDiscoveryService discoveryService,
            List<IndexerDescriptor> indexerDescriptors
    ) {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.registry = Objects.requireNonNull(registry, "registry");
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        CodeKnowledgeSnapshotStore snapshots = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.reconciler = new ProjectIndexStateReconciler(snapshots, this.stateStore);
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.historyDirectory = normalizedHome.resolve("cli-index-history");
        this.knownProviders = List.copyOf(Objects.requireNonNull(indexerDescriptors, "indexerDescriptors")).stream()
                .collect(Collectors.toUnmodifiableMap(IndexerDescriptor::id, Function.identity()));
    }

    public List<ProjectView> listProjects() throws IOException {
        List<ProjectView> projects = new ArrayList<>();
        for (RegisteredProject project : registry.listProjects()) projects.add(view(project));
        return List.copyOf(projects);
    }

    public ProjectView inspectProject(String projectIdentifier) throws IOException {
        return view(projectResolver.resolve(projectIdentifier));
    }

    public ProjectView view(RegisteredProject project) throws IOException {
        Objects.requireNonNull(project, "project");
        boolean rootAvailable = Files.isDirectory(project.rootPath());
        List<String> languages = List.of();
        List<String> buildSystems = List.of();
        int moduleCount = 0;
        if (rootAvailable) {
            ProjectDiscovery discovery = discoveryService.discover(project.rootPath());
            languages = discovery.languages().stream().map(Enum::name).sorted().toList();
            buildSystems = discovery.buildSystems().stream().map(Enum::name).sorted().toList();
            moduleCount = discovery.modules().size();
        }

        ProjectIndexStateReconciler.Reconciliation consistency = reconciler.reconcile(project.id());
        Optional<CodeKnowledgeSnapshot> active = consistency.activeSnapshot();
        String activeSnapshotId = active.map(CodeKnowledgeSnapshot::snapshotId).orElse(null);
        Optional<ProjectIndexState> persistedState = consistency.projectState();
        String indexState = persistedState
                .map(state -> state.availability().name())
                .orElse(active.isPresent() ? ProjectIndexState.Availability.READY.name()
                        : ProjectIndexState.Availability.NEVER_INDEXED.name());

        Optional<IndexingRun> activeRun = activeSnapshotId == null
                ? Optional.empty()
                : stateStore.listRuns(project.id()).stream()
                    .filter(run -> run.status() == IndexingRun.Status.SUCCEEDED)
                    .filter(run -> run.activeSnapshotAfter().filter(activeSnapshotId::equals).isPresent())
                    .max(Comparator.comparing(run -> run.completedAt().orElse(run.createdAt())));

        Optional<IndexHistory> manualHistory = readHistory(project.id()).filter(candidate ->
                activeSnapshotId != null && activeSnapshotId.equals(candidate.snapshotId()));
        String lastSuccessfulIndexAt = activeRun.flatMap(IndexingRun::completedAt).map(Instant::toString)
                .orElseGet(() -> manualHistory.map(value -> value.completedAt().toString()).orElse(null));
        String providerId = activeRun.map(ProjectInspectionService::providerIds).filter(value -> !value.isBlank())
                .orElseGet(() -> manualHistory.map(IndexHistory::providerId).orElse(null));
        String providerVersion = activeRun.map(this::providerVersions).filter(value -> !value.isBlank())
                .orElseGet(() -> manualHistory.map(IndexHistory::providerVersion).orElse(null));

        return new ProjectView(project.id().toString(), project.displayName(), project.rootPath().toString(), rootAvailable,
                languages, buildSystems, moduleCount, indexState, activeSnapshotId, lastSuccessfulIndexAt,
                providerId, providerVersion);
    }

    private static String providerIds(IndexingRun run) {
        return run.executions().stream().map(IndexingRun.IndexerExecution::indexerId).distinct().sorted()
                .collect(Collectors.joining(","));
    }

    private String providerVersions(IndexingRun run) {
        return run.executions().stream().map(IndexingRun.IndexerExecution::indexerId).distinct().sorted()
                .map(id -> Optional.ofNullable(knownProviders.get(id)).map(IndexerDescriptor::version)
                        .map(version -> id + "@" + version).orElse(id + "@unknown"))
                .collect(Collectors.joining(","));
    }

    private Optional<IndexHistory> readHistory(UUID projectId) throws IOException {
        Path file = historyDirectory.resolve(projectId + ".properties");
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties properties = BoundedProperties.load(
                file, MAX_HISTORY_PROPERTIES_BYTES, 16, 64, 8192,
                "CLI index history metadata");
        return Optional.of(new IndexHistory(required(properties, "snapshotId", file), required(properties, "providerId", file),
                blankToNull(properties.getProperty("providerVersion")), Instant.parse(required(properties, "completedAt", file))));
    }

    private static String required(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing index property '" + key + "' in " + file);
        return value;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }

    public record ProjectView(String id, String name, String rootPath, boolean rootAvailable, List<String> languages,
                              List<String> buildSystems, int moduleCount, String indexState, String activeSnapshotId,
                              String lastSuccessfulIndexAt, String providerId, String providerVersion) {
        public ProjectView {
            requireText(id, "id"); requireText(name, "name"); requireText(rootPath, "rootPath");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            if (moduleCount < 0) throw new IllegalArgumentException("moduleCount must not be negative");
            requireText(indexState, "indexState");
        }
    }

    private record IndexHistory(String snapshotId, String providerId, String providerVersion, Instant completedAt) {
        private IndexHistory {
            requireText(snapshotId, "snapshotId"); requireText(providerId, "providerId");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }
}
