package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.discovery.ProjectDiscovery;
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
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Implémentation locale du parcours autonome M14. */
public final class LocalAutonomousIndexOperations implements AutonomousIndexOperations {

    private final MinosApplication application;
    private final LocalProjectRegistry projectRegistry;
    private final FileSymbolSnapshotStore snapshotStore;
    private final FileIndexStateStore stateStore;
    private final FileProjectFingerprintSnapshotStore fingerprintStore;
    private final ProviderRuntimeManager runtimeManager;
    private final SnapshotStager snapshotStager;
    private final SnapshotPromoter snapshotPromoter;
    private final ProjectFingerprintService fingerprintService;
    private final ProjectInvalidationService invalidationService;
    private final IncrementalIndexingPlanner planner;

    public LocalAutonomousIndexOperations(Path minosHome) throws IOException {
        this(MinosApplication.open(minosHome));
    }

    public LocalAutonomousIndexOperations(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
        this.projectRegistry = application.projectRegistry();
        this.snapshotStore = application.snapshotStore();
        this.stateStore = application.indexStateStore();
        this.fingerprintStore = application.fingerprintStore();
        this.runtimeManager = application.providerRuntimeManager();
        this.snapshotStager = application.snapshotStager();
        this.snapshotPromoter = application.snapshotPromoter();
        this.fingerprintService = application.fingerprintService();
        this.invalidationService = application.invalidationService();
        this.planner = application.incrementalIndexingPlanner();
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
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                executors, snapshotStager, snapshotPromoter, stateStore);

        IndexingRun run = forceFull
                ? lifecycle.execute(prepared.project().id(), prepared.project().rootPath(), prepared.negotiation())
                : lifecycle.executePlanned(
                        prepared.project().id(), prepared.project().rootPath(), prepared.negotiation(), prepared.plan())
                        .orElseThrow(() -> new IllegalStateException("planned execution unexpectedly produced no run"));

        if (run.status() != IndexingRun.Status.SUCCEEDED) {
            throw new IllegalStateException("indexing run " + run.id() + " failed: "
                    + run.message().orElse("provider/staging/promotion failure"));
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
        ProjectDiscovery discovery = application.discoveryService().discover(project.rootPath());
        ProjectFingerprint current = fingerprintService.capture(project.rootPath());
        ProjectIndexState indexState = alignedIndexState(project.id());
        Optional<ProjectFingerprintSnapshot> baseline;
        ProjectInvalidationAssessment invalidation;
        try {
            baseline = fingerprintStore.loadActive(project.id());
            invalidation = invalidationService.assess(indexState, baseline, current, discovery);
        } catch (IOException exception) {
            invalidation = new ProjectInvalidationAssessment(
                    project.id(),
                    indexState.activeSnapshotId(),
                    Optional.empty(),
                    ProjectInvalidationScope.FULL_REQUIRED,
                    List.of(ProjectInvalidationReason.FINGERPRINT_BASELINE_UNREADABLE),
                    Optional.empty(), List.of(), List.of(), List.of());
        }

        IndexerRegistry registry = application.indexerRegistry(providerOverride);
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
        UUID parsed = null;
        try {
            parsed = UUID.fromString(identifier);
        } catch (IllegalArgumentException ignored) {
            // display name path below; centralized resolution is M15-S5.
        }
        if (parsed != null) {
            UUID id = parsed;
            return projectRegistry.findProject(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown project: " + identifier));
        }
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
