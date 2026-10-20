package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectIndexStateReconciler;
import com.minos.application.resolution.ProjectResolver;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.discovery.ProjectDiscovery;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectInvalidationAssessment;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.incremental.ProjectInvalidationScope;
import com.minos.orchestration.IncrementalIndexingPlan;
import com.minos.orchestration.IncrementalIndexingPlanner;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRequirements;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState;
import com.minos.orchestration.ProjectInvalidationService;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.orchestration.ResumableRunSummary;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
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
public final class LocalAutonomousIndexOperations
        implements AutonomousIndexOperations, IndexResumeStatusSource, AutoCloseable {
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
    private final ResumableRunMarkers resumableRunMarkers;

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
        // ADR 0042 : marqueurs de run créés au même moment qu'avant, par la racine de composition.
        this.resumableRunMarkers = application.compositionRoot().resumableRunMarkers(application.home());
    }

    @Override
    public Optional<ResumableRunSummary> resumableRun(String projectId) {
        try {
            return ResumableRunSummary.of(stateStore, UUID.fromString(projectId));
        } catch (IllegalArgumentException notAProjectId) {
            return Optional.empty();
        }
    }

    @Override
    public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        // A dry run writes nothing (no lease, no state, no repair): with nothing to serialize against,
        // it observes like every other read instead of taking the project lifecycle lease.
        return prepare(projectIdentifier, providerOverride, forceFull, false).view();
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return execute(projectIdentifier, providerOverride, forceFull, IndexingResumePolicy.RESUME);
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull,
                                      IndexingResumePolicy resumePolicy) throws Exception {
        Objects.requireNonNull(resumePolicy, "resumePolicy");
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(project.id())) {
            // ADR 0039 sec. 6: --no-resume forces a complete run (it supersedes the interrupted run and indexes
            // everything), it is not only a resume policy: on an unchanged project it must not answer NO_CHANGES.
            boolean complete = forceFull || resumePolicy == IndexingResumePolicy.NO_RESUME;
            return executeLocked(projectIdentifier, providerOverride, complete, resumePolicy);
        }
    }

    private IndexExecutionView executeLocked(
            String projectIdentifier,
            String providerOverride,
            boolean forceFull,
            IndexingResumePolicy resumePolicy
    ) throws Exception {
        Prepared prepared = prepare(projectIdentifier, providerOverride, forceFull, true);
        requireProviderRuntimesReady(prepared);
        if (prepared.view().mode() == IndexingMode.NONE && !forceFull) {
            return unchangedProject(prepared, resumePolicy);
        }
        IndexingRun run = recoverPromotedRunIfNeeded(runLifecycle(prepared, forceFull, resumePolicy));
        if (run.status() != IndexingRun.Status.SUCCEEDED) {
            IllegalStateException failure = new IllegalStateException(failureMessage(run));
            compactSuppressingFailure(prepared.project().id(), failure);
            throw failure;
        }
        return succeededRun(prepared, run);
    }

    private static void requireProviderRuntimesReady(Prepared prepared) {
        for (ProviderView runtime : prepared.view().providerRuntimes()) {
            if (!"READY".equals(runtime.state())) {
                throw new IllegalStateException("provider runtime is not ready: " + runtime.id()
                        + (runtime.diagnostics().isEmpty() ? "" : " — " + String.join("; ", runtime.diagnostics())));
            }
        }
    }

    /** Nothing changed since the active snapshot: no run, only the post-success maintenance. */
    private IndexExecutionView unchangedProject(Prepared prepared, IndexingResumePolicy resumePolicy) {
        if (resumePolicy == IndexingResumePolicy.RESUME_ONLY) {
            throw new IllegalStateException("resume-only indexing refused: no changes to index");
        }
        String semanticDiagnostic = synchronizeSemanticIfConfigured(prepared.project().id());
        String retentionDiagnostic = compactAfterSuccess(prepared.project().id());
        return new IndexExecutionView(prepared.view(), null, "NO_CHANGES",
                prepared.indexState().activeSnapshotId().orElse(null), true,
                combineDiagnostics(semanticDiagnostic, retentionDiagnostic));
    }

    private IndexingRun runLifecycle(Prepared prepared, boolean forceFull, IndexingResumePolicy resumePolicy) {
        var executors = prepared.negotiation().selections().stream()
                .map(selection -> runtimeManager.executor(selection.indexer().id()))
                .map(executorDecorator)
                .map(executor -> Objects.requireNonNull(executor, "decorated executor"))
                .toList();
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                executors, snapshotStager, snapshotPromoter, stateStore, resumableRunMarkers);
        if (forceFull) {
            return lifecycle.execute(
                    prepared.project().id(),
                    prepared.project().rootPath(),
                    prepared.discovery(),
                    prepared.negotiation(),
                    resumePolicy
            );
        }
        return lifecycle.executePlanned(
                        prepared.project().id(),
                        prepared.project().rootPath(),
                        prepared.discovery(),
                        prepared.negotiation(),
                        prepared.plan(),
                        resumePolicy
                )
                .orElseThrow(() -> new IllegalStateException("planned execution unexpectedly produced no run"));
    }

    /** The fingerprint baseline moves to the new snapshot only when the workspace did not change meanwhile. */
    private IndexExecutionView succeededRun(Prepared prepared, IndexingRun run) throws IOException {
        ProjectFingerprint after = fingerprintService.capture(prepared.project().rootPath());
        boolean fingerprintPromoted = prepared.fingerprintBefore().equals(after);
        String diagnostic = null;
        if (fingerprintPromoted) {
            promoteFingerprint(prepared.project().id(), run.activeSnapshotAfter().orElseThrow(), after);
        } else {
            diagnostic = "workspace changed during indexing; fingerprint baseline was not promoted";
        }
        diagnostic = combineDiagnostics(diagnostic, synchronizeSemanticIfConfigured(prepared.project().id()));
        diagnostic = combineDiagnostics(diagnostic, compactAfterSuccess(prepared.project().id()));
        return new IndexExecutionView(prepared.view(), run.id().toString(), run.status().name(),
                run.activeSnapshotAfter().orElse(null), fingerprintPromoted, diagnostic, resumeView(run));
    }

    private void promoteFingerprint(UUID projectId, String activeSnapshotId, ProjectFingerprint after)
            throws IOException {
        try {
            fingerprintStore.publish(projectId, activeSnapshotId, after);
            fingerprintStore.promote(projectId, activeSnapshotId);
        } catch (IOException fingerprintFailure) {
            compactSuppressingFailure(projectId, fingerprintFailure);
            throw fingerprintFailure;
        }
    }

    /** Retention still runs after a failure; its own failure is attached to the one being reported. */
    private void compactSuppressingFailure(UUID projectId, Throwable failure) {
        try {
            application.retentionService().compact(projectId);
        } catch (IOException retentionFailure) {
            failure.addSuppressed(retentionFailure);
        }
    }

    /**
     * The public line of a run that did not succeed. It is built from parts that are always safe (the run identity and
     * phase) and joins the recorded cause only when the shared rule finds it safe: a cause naming an absolute path
     * would otherwise turn the whole line into the bare exception class name. The withheld cause stays in the run
     * record, named relative to {@code MINOS_HOME}.
     */
    static String failureMessage(IndexingRun run) {
        String outcome = run.status() == IndexingRun.Status.INTERRUPTED
                ? " was interrupted and can be resumed"
                : " failed";
        String cause = run.message().orElse("provider/staging/promotion failure");
        String detail = PublicErrorMessages.looksSensitive(cause)
                ? "its cause names a local path or a credential and is recorded in MINOS_HOME/index-state/runs, "
                        + "provider logs in MINOS_HOME/runs/" + run.id()
                : cause;
        return "indexing run " + run.id() + outcome + " during " + run.phase() + ": " + detail;
    }

    private static ResumeView resumeView(IndexingRun run) {
        return run.resume()
                .map(trace -> new ResumeView(trace.attempt(), trace.reusedTargets(), trace.reexecutedTargets(),
                        trace.refusalReason().orElse(null)))
                .orElse(null);
    }

    /**
     * The snapshot a run that did not succeed has nevertheless promoted: its staged snapshot, when that is the active
     * one. Only the identity of the active snapshot is read; the snapshot is not loaded.
     */
    static Optional<String> promotedSnapshotId(IndexingRun run, CodeKnowledgeSnapshotStore snapshots)
            throws IOException {
        if (run.status() == IndexingRun.Status.SUCCEEDED || run.stagedSnapshotId().isEmpty()) {
            return Optional.empty();
        }
        return snapshots.activeSnapshotId(run.projectId()).filter(run.stagedSnapshotId().orElseThrow()::equals);
    }

    private IndexingRun recoverPromotedRunIfNeeded(IndexingRun run) throws IOException {
        Optional<String> promoted = promotedSnapshotId(run, snapshotStore);
        if (promoted.isEmpty()) {
            return run;
        }

        String activeSnapshotId = promoted.orElseThrow();
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
                Optional.of("run recovered from authoritative snapshot after post-promotion state persistence failure"),
                run.runFormatVersion());
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
            return report.state() == com.minos.application.semantic.SemanticIndexService.State.READY
                    ? null
                    : "semantic index state after structured indexing: " + report.state();
        } catch (Exception exception) {
            String message = exception.getMessage();
            return "semantic index refresh failed without invalidating structured snapshot: "
                    + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        }
    }

    /**
     * Retention is maintenance after an index that already succeeded. Its lock is bounded, so it can time out
     * behind a concurrent compaction: that must not turn a successful index into a failure. The diagnostic
     * never carries the cause, which may name a path.
     */
    private String compactAfterSuccess(java.util.UUID projectId) {
        try {
            application.retentionService().compact(projectId);
            return null;
        } catch (IOException retentionFailure) {
            return "storage retention did not run (its lock was busy or unavailable); it runs after the next indexing";
        }
    }

    private static String combineDiagnostics(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "; " + second;
    }

    /** {@code persist} is false for a dry run: the state is observed (a repair or a first state is computed, never saved). */
    private Prepared prepare(String projectIdentifier, String providerOverride, boolean forceFull, boolean persist)
            throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        // MINOS-AUD-D06: the reference fingerprint is captured BEFORE the discovery, so a structure created between the
        // two is in the post-run fingerprint without being in this one and the baseline is not promoted (ADR 0014).
        ProjectFingerprint current = fingerprintService.capture(project.rootPath());
        ProjectDiscovery discovery = application.discoveryService().discover(project.rootPath());
        ProjectIndexState indexState = alignedIndexState(project.id(), persist);
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

    private ProjectIndexState alignedIndexState(UUID projectId, boolean persist) throws IOException {
        ProjectIndexStateReconciler.ReconciledState reconciliation = persist
                ? projectIndexStateReconciler.reconcileState(projectId)
                : projectIndexStateReconciler.observeState(projectId);
        if (reconciliation.projectState().isPresent()) {
            return reconciliation.projectState().orElseThrow();
        }
        ProjectIndexState neverIndexed = ProjectIndexState.neverIndexed(projectId, Instant.now());
        if (persist) {
            stateStore.saveProjectState(neverIndexed);
        }
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
