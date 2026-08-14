package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectIndexStateReconciler;
import com.minos.application.ProjectResolver;
import com.minos.discovery.ProjectDiscovery;
import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.incremental.IncrementalIndexingPlanner;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectInvalidationAssessment;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.incremental.ProjectInvalidationScope;
import com.minos.incremental.ProjectInvalidationService;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Autonomous indexing adapter over the selected MINOS storage backend. */
public final class LocalAutonomousIndexOperations implements AutonomousIndexOperations, AutoCloseable {
    private final MinosApplication application;
    private final MinosApplication ownedApplication;
    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;
    private final ProjectIndexStateReconciler projectIndexStateReconciler;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final ProviderRuntimeManager runtimeManager;
    private final SnapshotStager snapshotStager;
    private final SnapshotPromoter snapshotPromoter;
    private final ProjectFingerprintService fingerprintService;
    private final ProjectInvalidationService invalidationService;
    private final IncrementalIndexingPlanner planner;
    private final UnaryOperator<IndexerExecutor> executorDecorator;

    public LocalAutonomousIndexOperations(Path minosHome) throws IOException {
        this(MinosApplication.open(minosHome), UnaryOperator.identity(), true);
    }

    public LocalAutonomousIndexOperations(MinosApplication application) {
        this(application, UnaryOperator.identity(), false);
    }

    public LocalAutonomousIndexOperations(
            MinosApplication application,
            UnaryOperator<IndexerExecutor> executorDecorator
    ) {
        this(application, executorDecorator, false);
    }

    private LocalAutonomousIndexOperations(
            MinosApplication application,
            UnaryOperator<IndexerExecutor> executorDecorator,
            boolean ownsApplication
    ) {
        this.application = Objects.requireNonNull(application, "application");
        this.ownedApplication = ownsApplication ? this.application : null;
        this.projectResolver = new ProjectResolver(application.projectRegistry());
        this.snapshotStore = application.snapshotStore();
        this.stateStore = application.indexStateStore();
        this.projectIndexStateReconciler = new ProjectIndexStateReconciler(snapshotStore, stateStore);
        this.fingerprintStore = application.fingerprintStore();
        this.runtimeManager = application.providerRuntimeManager();
        this.snapshotStager = application.snapshotStager();
        this.snapshotPromoter = application.snapshotPromoter();
        this.fingerprintService = application.fingerprintService();
        this.invalidationService = application.invalidationService();
        this.planner = application.incrementalIndexingPlanner();
        this.executorDecorator = Objects.requireNonNull(executorDecorator, "executorDecorator");
    }

    @Override
    public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(project.id())) {
            return prepare(projectIdentifier, providerOverride, forceFull).view();
        }
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(project.id())) {
            return executeLocked(projectIdentifier, providerOverride, forceFull);
        }
    }

    private IndexExecutionView executeLocked(
            String projectIdentifier,
            String providerOverride,
            boolean forceFull
    ) throws Exception {
        Prepared prepared = prepare(projectIdentifier, providerOverride, forceFull);
        for (ProviderView runtime : prepared.view().providerRuntimes()) {
            if (!"READY".equals(runtime.state())) {
                throw new IllegalStateException("provider runtime is not ready: " + runtime.id()
                        + (runtime.diagnostics().isEmpty() ? "" : " — " + String.join("; ", runtime.diagnostics())));
            }
        }
        if (prepared.view().mode() == IndexingMode.NONE && !forceFull) {
            String semanticDiagnostic = synchronizeSemanticIfConfigured(prepared.project().id());
            application.retentionService().compact(prepared.project().id());
            return new IndexExecutionView(prepared.view(), null, "NO_CHANGES",
                    prepared.indexState().activeSnapshotId().orElse(null), true, semanticDiagnostic);
        }
        var executors = prepared.negotiation().selections().stream()
                .map(selection -> runtimeManager.executor(selection.indexer().id()))
                .map(executorDecorator)
                .map(executor -> Objects.requireNonNull(executor, "decorated executor"))
                .toList();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(executors, snapshotStager, snapshotPromoter, stateStore);
        IndexingRun run = forceFull
                ? lifecycle.execute(
                        prepared.project().id(),
                        prepared.project().rootPath(),
                        prepared.discovery(),
                        prepared.negotiation()
                )
                : lifecycle.executePlanned(
                                prepared.project().id(),
                                prepared.project().rootPath(),
                                prepared.discovery(),
                                prepared.negotiation(),
                                prepared.plan()
                        )
                        .orElseThrow(() -> new IllegalStateException("planned execution unexpectedly produced no run"));
        run = recoverPromotedRunIfNeeded(run);
        if (run.status() != IndexingRun.Status.SUCCEEDED) {
            IllegalStateException failure = new IllegalStateException(
                    "indexing run " + run.id() + " failed: "
                            + run.message().orElse("provider/staging/promotion failure"));
            try {
                application.retentionService().compact(prepared.project().id());
            } catch (IOException retentionFailure) {
                failure.addSuppressed(retentionFailure);
            }
            throw failure;
        }
        ProjectFingerprint after = fingerprintService.capture(prepared.project().rootPath());
        boolean stable = prepared.fingerprintBefore().equals(after);
        boolean fingerprintPromoted = false;
        String diagnostic = null;
        if (stable) {
            String activeSnapshotId = run.activeSnapshotAfter().orElseThrow();
            try {
                fingerprintStore.publish(prepared.project().id(), activeSnapshotId, after);
                fingerprintStore.promote(prepared.project().id(), activeSnapshotId);
            } catch (IOException fingerprintFailure) {
                try {
                    application.retentionService().compact(prepared.project().id());
                } catch (IOException retentionFailure) {
                    fingerprintFailure.addSuppressed(retentionFailure);
                }
                throw fingerprintFailure;
            }
            fingerprintPromoted = true;
        } else {
            diagnostic = "workspace changed during indexing; fingerprint baseline was not promoted";
        }
        diagnostic = combineDiagnostics(diagnostic, synchronizeSemanticIfConfigured(prepared.project().id()));
        application.retentionService().compact(prepared.project().id());
        return new IndexExecutionView(prepared.view(), run.id().toString(), run.status().name(),
                run.activeSnapshotAfter().orElse(null), fingerprintPromoted, diagnostic);
    }

    private IndexingRun recoverPromotedRunIfNeeded(IndexingRun run) throws IOException {
        if (run.status() == IndexingRun.Status.SUCCEEDED || run.stagedSnapshotId().isEmpty()) {
            return run;
        }
        Optional<CodeKnowledgeSnapshot> active = snapshotStore.loadActiveKnowledge(run.projectId());
        if (active.isEmpty()
                || !run.stagedSnapshotId().orElseThrow().equals(active.orElseThrow().snapshotId())) {
            return run;
        }

        String activeSnapshotId = active.orElseThrow().snapshotId();
        Instant completedAt = run.completedAt().orElseGet(Instant::now);
        IndexingRun recovered = new IndexingRun(
                run.id(),
                run.projectId(),
                IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED,
                run.createdAt(),
                Optional.of(completedAt),
                run.executions(),
                run.stagedSnapshotId(),
                run.activeSnapshotBefore(),
                Optional.of(activeSnapshotId),
                Optional.of("run recovered from authoritative snapshot after post-promotion state persistence failure"));
        stateStore.saveRun(recovered);
        stateStore.saveProjectState(new ProjectIndexState(
                run.projectId(),
                ProjectIndexState.Availability.READY,
                Optional.of(activeSnapshotId),
                Optional.of(run.id()),
                completedAt,
                Optional.of("state reconciled after successful snapshot promotion")));
        return recovered;
    }

    @Override public List<ProviderView> providers() {
        return runtimeManager.list().stream().map(LocalAutonomousIndexOperations::view).toList();
    }

    @Override public ProviderView installProvider(String providerId) throws Exception { return view(runtimeManager.install(providerId)); }

    private String synchronizeSemanticIfConfigured(UUID projectId) {
        if (application.semanticIndexService().embeddingProvider().isEmpty()) return null;
        try {
            var report = application.semanticIndexService().synchronize(projectId);
            return report.state() == com.minos.semantic.SemanticIndexService.State.READY
                    ? null
                    : "semantic index state after structured indexing: " + report.state();
        } catch (Exception exception) {
            String message = exception.getMessage();
            return "semantic index refresh failed without invalidating structured snapshot: "
                    + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        }
    }

    private static String combineDiagnostics(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "; " + second;
    }

    private Prepared prepare(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        ProjectDiscovery discovery = application.discoveryService().discover(project.rootPath());
        ProjectFingerprint current = fingerprintService.capture(project.rootPath());
        ProjectIndexState indexState = alignedIndexState(project.id());
        Optional<ProjectFingerprintSnapshot> baseline;
        ProjectInvalidationAssessment invalidation;
        try {
            baseline = fingerprintStore.loadActive(project.id());
            invalidation = invalidationService.assess(indexState, baseline, current, discovery);
        } catch (IOException exception) {
            invalidation = new ProjectInvalidationAssessment(project.id(), indexState.activeSnapshotId(), Optional.empty(),
                    ProjectInvalidationScope.FULL_REQUIRED,
                    List.of(ProjectInvalidationReason.FINGERPRINT_BASELINE_UNREADABLE),
                    Optional.empty(), List.of(), List.of(), List.of());
        }
        IndexerRegistry registry = application.indexerRegistry(providerOverride);
        IndexingRequirements baselineRequirements = IndexingRequirements.baseline();
        IndexingRequirements requirements = providerOverride == null || providerOverride.isBlank()
                ? baselineRequirements
                : new IndexingRequirements(baselineRequirements.requiredCapabilities(), true);
        IndexerNegotiationResult negotiation = registry.negotiate(discovery, requirements);
        if (!negotiation.complete()) {
            throw new IllegalStateException("no qualified provider covers languages: "
                    + negotiation.uncoveredLanguages().stream().map(Enum::name).sorted().toList());
        }
        IncrementalIndexingPlan plan = planner.plan(invalidation, negotiation);
        IndexingMode effectiveMode = forceFull ? IndexingMode.FULL : plan.mode();
        List<String> reasons = new ArrayList<>(plan.reasons().stream().map(Enum::name).toList());
        if (forceFull) reasons.add("FORCED_FULL");
        reasons = reasons.stream().distinct().sorted().toList();
        List<String> providers = negotiation.selections().stream().map(selection -> selection.indexer().id()).sorted().toList();
        List<ProviderView> runtimes = providers.stream().map(runtimeManager::inspect).map(LocalAutonomousIndexOperations::view).toList();
        IndexPlanView view = new IndexPlanView(
                project.id().toString(), project.displayName(), project.rootPath().toString(),
                discovery.languages().stream().map(Enum::name).sorted().toList(),
                discovery.buildSystems().stream().map(Enum::name).sorted().toList(),
                providers, runtimes, effectiveMode, reasons, forceFull ? List.of() : plan.changedFiles(), forceFull);
        return new Prepared(project, discovery, negotiation, plan, indexState, current, view);
    }

    private ProjectIndexState alignedIndexState(UUID projectId) throws IOException {
        ProjectIndexStateReconciler.Reconciliation reconciliation = projectIndexStateReconciler.reconcile(projectId);
        if (reconciliation.projectState().isPresent()) {
            return reconciliation.projectState().orElseThrow();
        }
        ProjectIndexState neverIndexed = ProjectIndexState.neverIndexed(projectId, Instant.now());
        stateStore.saveProjectState(neverIndexed);
        return neverIndexed;
    }

    private static ProviderView view(ProviderRuntimeStatus status) {
        return new ProviderView(status.providerId(), status.version(), status.state().name(),
                status.executable().map(Path::toString).orElse(null), status.diagnostics(), status.requiredByDefault());
    }

    private record Prepared(
            RegisteredProject project,
            ProjectDiscovery discovery,
            IndexerNegotiationResult negotiation,
            IncrementalIndexingPlan plan,
            ProjectIndexState indexState,
            ProjectFingerprint fingerprintBefore,
            IndexPlanView view
    ) { }

    @Override
    public void close() throws IOException {
        if (ownedApplication != null) ownedApplication.close();
    }
}
