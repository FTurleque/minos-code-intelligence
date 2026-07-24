package com.minos.cli;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectInvalidationAssessment;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.incremental.ProjectInvalidationScope;
import com.minos.incremental.ProjectInvalidationService;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Implémentation locale du parcours autonome M14. */
public final class LocalAutonomousIndexOperations implements AutonomousIndexOperations {

    private final LocalProjectRegistry projectRegistry;
    private final FileSymbolSnapshotStore snapshotStore;
    private final FileIndexStateStore stateStore;
    private final FileProjectFingerprintSnapshotStore fingerprintStore;
    private final ManagedScipProviderRuntimeManager runtimeManager;
    private final ProjectDiscoveryService discoveryService = new ProjectDiscoveryService();
    private final ProjectFingerprintService fingerprintService = new ProjectFingerprintService();
    private final ProjectInvalidationService invalidationService = new ProjectInvalidationService();
    private final IncrementalIndexingPlanner planner = new IncrementalIndexingPlanner();
    private final Path home;

    public LocalAutonomousIndexOperations(Path minosHome) throws IOException {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.projectRegistry = new LocalProjectRegistry(home.resolve("registry"));
        this.snapshotStore = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        this.stateStore = new FileIndexStateStore(home.resolve("index-state"));
        this.fingerprintStore = new FileProjectFingerprintSnapshotStore(home.resolve("fingerprint-snapshots"));
        this.runtimeManager = new ManagedScipProviderRuntimeManager(home);
    }

    @Override
    public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return prepare(projectIdentifier, providerOverride, forceFull).view();
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        Prepared prepared = prepare(projectIdentifier, providerOverride, forceFull);
        for (ProviderView runtime : prepared.view().providerRuntimes()) {
            if (!"READY".equals(runtime.state())) {
                throw new IllegalStateException("provider runtime is not ready: " + runtime.id()
                        + (runtime.diagnostics().isEmpty() ? "" : " — " + String.join("; ", runtime.diagnostics())));
            }
        }
        if (prepared.view().mode() == IndexingMode.NONE && !forceFull) {
            return new IndexExecutionView(
                    prepared.view(), null, "NO_CHANGES",
                    prepared.indexState().activeSnapshotId().orElse(null), true, null);
        }

        var executors = prepared.negotiation().selections().stream()
                .map(selection -> runtimeManager.executor(selection.indexer().id()))
                .toList();
        ScipProjectSnapshotLifecycle snapshots = new ScipProjectSnapshotLifecycle(home);
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                executors, snapshots, snapshots, stateStore);

        IndexingRun run = forceFull
                ? lifecycle.execute(prepared.project().id(), prepared.project().rootPath(), prepared.negotiation())
                : lifecycle.executePlanned(
                        prepared.project().id(), prepared.project().rootPath(), prepared.negotiation(), prepared.plan())
                        .orElseThrow(() -> new IllegalStateException("planned execution unexpectedly produced no run"));

        if (run.status() != IndexingRun.Status.SUCCEEDED) {
            return new IndexExecutionView(
                    prepared.view(), run.id().toString(), run.status().name(),
                    run.activeSnapshotAfter().orElse(null), false, run.message().orElse(null));
        }

        ProjectFingerprint after = fingerprintService.capture(prepared.project().rootPath());
        boolean stable = prepared.fingerprintBefore().equals(after);
        boolean fingerprintPromoted = false;
        String diagnostic = null;
        if (stable) {
            String activeSnapshotId = run.activeSnapshotAfter().orElseThrow();
            fingerprintStore.publish(prepared.project().id(), activeSnapshotId, after);
            fingerprintStore.promote(prepared.project().id(), activeSnapshotId);
            fingerprintPromoted = true;
        } else {
            diagnostic = "workspace changed during indexing; fingerprint baseline was not promoted";
        }
        return new IndexExecutionView(
                prepared.view(), run.id().toString(), run.status().name(),
                run.activeSnapshotAfter().orElse(null), fingerprintPromoted, diagnostic);
    }

    @Override
    public List<ProviderView> providers() {
        return runtimeManager.list().stream().map(LocalAutonomousIndexOperations::view).toList();
    }

    @Override
    public ProviderView installProvider(String providerId) throws Exception {
        return view(runtimeManager.install(providerId));
    }

    private Prepared prepare(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        RegisteredProject project = resolveProject(projectIdentifier);
        ProjectDiscovery discovery = discoveryService.discover(project.rootPath());
        ProjectFingerprint current = fingerprintService.capture(project.rootPath());
        ProjectIndexState indexState = alignedIndexState(project.id());
        Optional<ProjectFingerprintSnapshot> baseline;
        ProjectInvalidationAssessment invalidation;
        try {
            baseline = fingerprintStore.loadActive(project.id());
            invalidation = invalidationService.assess(indexState, baseline, current, discovery);
        } catch (IOException exception) {
            baseline = Optional.empty();
            invalidation = new ProjectInvalidationAssessment(
                    project.id(),
                    indexState.activeSnapshotId(),
                    Optional.empty(),
                    ProjectInvalidationScope.FULL_REQUIRED,
                    List.of(ProjectInvalidationReason.FINGERPRINT_BASELINE_UNREADABLE),
                    Optional.empty(), List.of(), List.of(), List.of());
        }

        IndexerRegistry registry = providerRegistry(providerOverride);
        IndexerNegotiationResult negotiation = registry.negotiate(discovery, IndexingRequirements.baseline());
        if (!negotiation.complete()) {
            throw new IllegalStateException("no qualified provider covers languages: "
                    + negotiation.uncoveredLanguages().stream().map(Enum::name).sorted().toList());
        }
        IncrementalIndexingPlan plan = planner.plan(invalidation, negotiation);
        IndexingMode effectiveMode = forceFull ? IndexingMode.FULL : plan.mode();
        List<String> reasons = new ArrayList<>(plan.reasons().stream().map(Enum::name).toList());
        if (forceFull) {
            reasons.add("FORCED_FULL");
        }
        reasons = reasons.stream().distinct().sorted().toList();
        List<String> providers = negotiation.selections().stream()
                .map(selection -> selection.indexer().id()).sorted().toList();
        List<ProviderView> runtimes = providers.stream()
                .map(runtimeManager::inspect).map(LocalAutonomousIndexOperations::view).toList();

        IndexPlanView view = new IndexPlanView(
                project.id().toString(), project.displayName(), project.rootPath().toString(),
                discovery.languages().stream().map(Enum::name).sorted().toList(),
                discovery.buildSystems().stream().map(Enum::name).sorted().toList(),
                providers, runtimes, effectiveMode, reasons,
                forceFull ? List.of() : plan.changedFiles(), forceFull);
        return new Prepared(project, negotiation, plan, indexState, current, view);
    }

    private IndexerRegistry providerRegistry(String providerOverride) {
        List<IndexerDescriptor> descriptors = ScipIndexerCatalog.qualifiedM1Descriptors();
        IndexerRegistry registry = new IndexerRegistry();
        if (providerOverride == null || providerOverride.isBlank()) {
            registry.registerAll(descriptors);
            return registry;
        }
        IndexerDescriptor descriptor = descriptors.stream()
                .filter(candidate -> providerOverride.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown provider override: " + providerOverride));
        registry.register(descriptor);
        return registry;
    }

    private ProjectIndexState alignedIndexState(UUID projectId) throws IOException {
        Optional<ProjectIndexState> stored = stateStore.findProjectState(projectId);
        if (stored.isPresent()) {
            return stored.orElseThrow();
        }
        Optional<CodeKnowledgeSnapshot> active = snapshotStore.loadActiveKnowledge(projectId);
        ProjectIndexState aligned = active
                .map(snapshot -> new ProjectIndexState(
                        projectId, ProjectIndexState.Availability.READY,
                        Optional.of(snapshot.snapshotId()), Optional.empty(), Instant.now(),
                        Optional.of("state aligned from active symbol snapshot")))
                .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, Instant.now()));
        stateStore.saveProjectState(aligned);
        return aligned;
    }

    private RegisteredProject resolveProject(String identifier) throws IOException {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("project identifier must not be blank");
        }
        try {
            UUID id = UUID.fromString(identifier);
            return projectRegistry.findProject(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown project: " + identifier));
        } catch (IllegalArgumentException notUuid) {
            List<RegisteredProject> matches = projectRegistry.listProjects().stream()
                    .filter(project -> identifier.equals(project.displayName()))
                    .toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("unknown project: " + identifier);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("ambiguous project name, use its UUID: " + identifier);
            }
            return matches.getFirst();
        }
    }

    private static ProviderView view(ProviderRuntimeStatus status) {
        return new ProviderView(
                status.providerId(), status.version(), status.state().name(),
                status.executable().map(Path::toString).orElse(null), status.diagnostics());
    }

    private record Prepared(
            RegisteredProject project,
            IndexerNegotiationResult negotiation,
            IncrementalIndexingPlan plan,
            ProjectIndexState indexState,
            ProjectFingerprint fingerprintBefore,
            IndexPlanView view
    ) {
    }
}
