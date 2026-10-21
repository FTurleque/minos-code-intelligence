package com.minos.application.indexing;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectIndexStateReconciler;
import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.SemanticIndexService;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
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
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The single indexing orchestration of the product (AUD-ARC-02, ADR 0057 sec. 2): preparation (reference
 * fingerprint, discovery, aligned state, invalidation, negotiation, plan), execution under the project lease, recovery
 * of a promoted run, fingerprint promotion, semantic synchronization and retention on every terminal path. Surfaces
 * delegate to it and only project its result; it names ports of the engine and application services, never an
 * adapter.
 */
public final class ProjectIndexingUseCase {

    static final String BASELINE_UNREADABLE_DIAGNOSTIC =
            "active fingerprint baseline unreadable; a full indexing was required";

    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;
    private final ProjectIndexStateReconciler projectIndexStateReconciler;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final ProviderRuntimeManager runtimeManager;
    private final SnapshotStager snapshotStager;
    private final SnapshotPromoter snapshotPromoter;
    private final ResumableRunMarkers resumableRunMarkers;
    private final ProjectDiscoveryService discoveryService;
    private final ProjectFingerprintService fingerprintService;
    private final ProjectInvalidationService invalidationService;
    private final IncrementalIndexingPlanner planner;
    private final Function<String, IndexerRegistry> indexerRegistries;
    private final StorageRetentionService retentionService;
    private final SemanticRefresh semanticRefresh;
    private final UnaryOperator<IndexerExecutor> executorDecorator;

    /** Refreshes the semantic index after a structured index; returns a public diagnostic, or {@code null}. */
    @FunctionalInterface
    public interface SemanticRefresh {
        String refresh(UUID projectId);
    }

    /** The ports the orchestration needs; production takes them from {@link MinosApplication}. */
    public record Dependencies(
            ProjectRegistry projectRegistry,
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore stateStore,
            ProjectFingerprintSnapshotStore fingerprintStore,
            ProviderRuntimeManager runtimeManager,
            SnapshotStager snapshotStager,
            SnapshotPromoter snapshotPromoter,
            ResumableRunMarkers resumableRunMarkers,
            ProjectDiscoveryService discoveryService,
            ProjectFingerprintService fingerprintService,
            ProjectInvalidationService invalidationService,
            IncrementalIndexingPlanner planner,
            Function<String, IndexerRegistry> indexerRegistries,
            StorageRetentionService retentionService,
            SemanticRefresh semanticRefresh
    ) {
        public Dependencies {
            Objects.requireNonNull(projectRegistry, "projectRegistry");
            Objects.requireNonNull(snapshotStore, "snapshotStore");
            Objects.requireNonNull(stateStore, "stateStore");
            Objects.requireNonNull(fingerprintStore, "fingerprintStore");
            Objects.requireNonNull(runtimeManager, "runtimeManager");
            Objects.requireNonNull(snapshotStager, "snapshotStager");
            Objects.requireNonNull(snapshotPromoter, "snapshotPromoter");
            Objects.requireNonNull(resumableRunMarkers, "resumableRunMarkers");
            Objects.requireNonNull(discoveryService, "discoveryService");
            Objects.requireNonNull(fingerprintService, "fingerprintService");
            Objects.requireNonNull(invalidationService, "invalidationService");
            Objects.requireNonNull(planner, "planner");
            Objects.requireNonNull(indexerRegistries, "indexerRegistries");
            Objects.requireNonNull(retentionService, "retentionService");
            Objects.requireNonNull(semanticRefresh, "semanticRefresh");
        }
    }

    /**
     * A prepared indexing: what a dry run shows and what an execution starts from. {@code baselineDiagnostic} is
     * present when the active fingerprint baseline could not be read (the plan is then complete).
     */
    public record Preparation(
            RegisteredProject project,
            ProjectDiscovery discovery,
            IndexerNegotiationResult negotiation,
            IncrementalIndexingPlan plan,
            ProjectIndexState indexState,
            ProjectFingerprint fingerprintBefore,
            IndexingMode effectiveMode,
            List<String> reasons,
            List<String> changedFiles,
            List<String> providerIds,
            List<ProviderRuntimeStatus> providerRuntimes,
            boolean forcedFull,
            Optional<String> baselineDiagnostic
    ) {
        public Preparation {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(discovery, "discovery");
            Objects.requireNonNull(negotiation, "negotiation");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(indexState, "indexState");
            Objects.requireNonNull(fingerprintBefore, "fingerprintBefore");
            Objects.requireNonNull(effectiveMode, "effectiveMode");
            reasons = List.copyOf(reasons);
            changedFiles = List.copyOf(changedFiles);
            providerIds = List.copyOf(providerIds);
            providerRuntimes = List.copyOf(providerRuntimes);
            Objects.requireNonNull(baselineDiagnostic, "baselineDiagnostic");
        }
    }

    /**
     * The outcome of an execution that did not fail. {@code status} is the run status, or {@code NO_CHANGES} when
     * nothing had to be indexed (then {@code run} is empty).
     */
    public record Outcome(
            Preparation preparation,
            Optional<IndexingRun> run,
            String status,
            Optional<String> activeSnapshotId,
            boolean fingerprintPromoted,
            Optional<String> diagnostic
    ) {
        public static final String NO_CHANGES = "NO_CHANGES";

        public Outcome {
            Objects.requireNonNull(preparation, "preparation");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(activeSnapshotId, "activeSnapshotId");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    public ProjectIndexingUseCase(Dependencies dependencies, UnaryOperator<IndexerExecutor> executorDecorator) {
        Objects.requireNonNull(dependencies, "dependencies");
        this.projectResolver = new ProjectResolver(dependencies.projectRegistry());
        this.snapshotStore = dependencies.snapshotStore();
        this.stateStore = dependencies.stateStore();
        this.projectIndexStateReconciler = new ProjectIndexStateReconciler(snapshotStore, stateStore);
        this.fingerprintStore = dependencies.fingerprintStore();
        this.runtimeManager = dependencies.runtimeManager();
        this.snapshotStager = dependencies.snapshotStager();
        this.snapshotPromoter = dependencies.snapshotPromoter();
        this.resumableRunMarkers = dependencies.resumableRunMarkers();
        this.discoveryService = dependencies.discoveryService();
        this.fingerprintService = dependencies.fingerprintService();
        this.invalidationService = dependencies.invalidationService();
        this.planner = dependencies.planner();
        this.indexerRegistries = dependencies.indexerRegistries();
        this.retentionService = dependencies.retentionService();
        this.semanticRefresh = dependencies.semanticRefresh();
        this.executorDecorator = Objects.requireNonNull(executorDecorator, "executorDecorator");
    }

    /** The production wiring: every port comes from the application (ADR 0042: markers from the composition root). */
    public static ProjectIndexingUseCase of(MinosApplication application, UnaryOperator<IndexerExecutor> executorDecorator) {
        Objects.requireNonNull(application, "application");
        return new ProjectIndexingUseCase(new Dependencies(
                application.projectRegistry(),
                application.snapshotStore(),
                application.indexStateStore(),
                application.fingerprintStore(),
                application.providerRuntimeManager(),
                application.snapshotStager(),
                application.snapshotPromoter(),
                application.compositionRoot().resumableRunMarkers(application.home()),
                application.discoveryService(),
                application.fingerprintService(),
                application.invalidationService(),
                application.incrementalIndexingPlanner(),
                application::indexerRegistry,
                application.retentionService(),
                semanticRefresh(application.semanticIndexService())), executorDecorator);
    }

    /** The semantic refresh of the product: skipped without an embedding provider, never fatal to the index. */
    public static SemanticRefresh semanticRefresh(SemanticIndexService semanticIndexService) {
        Objects.requireNonNull(semanticIndexService, "semanticIndexService");
        return projectId -> {
            if (semanticIndexService.embeddingProvider().isEmpty()) return null;
            try {
                var report = semanticIndexService.synchronize(projectId);
                return report.state() == SemanticIndexService.State.READY
                        ? null
                        : "semantic index state after structured indexing: " + report.state();
            } catch (Exception exception) {
                String message = exception.getMessage();
                return "semantic index refresh failed without invalidating structured snapshot: "
                        + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
            }
        };
    }

    /**
     * A dry run: it writes nothing (no lease, no state, no repair). With nothing to serialize against, it observes
     * like every other read instead of taking the project lifecycle lease.
     */
    public Preparation plan(String projectIdentifier, String providerOverride, boolean forceFull) throws IOException {
        return prepare(projectIdentifier, providerOverride, forceFull, false);
    }

    /** Executes an indexing under the project lease; a run that does not succeed is an {@link IllegalStateException}. */
    public Outcome execute(String projectIdentifier, String providerOverride, boolean forceFull,
                           IndexingResumePolicy resumePolicy) throws IOException {
        Objects.requireNonNull(resumePolicy, "resumePolicy");
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        try (IndexStateStore.ProjectLease _ = stateStore.acquireProjectLease(project.id())) {
            // ADR 0039 sec. 6: --no-resume forces a complete run (it supersedes the interrupted run and indexes
            // everything), it is not only a resume policy: on an unchanged project it must not answer NO_CHANGES.
            boolean complete = forceFull || resumePolicy == IndexingResumePolicy.NO_RESUME;
            return executeLocked(projectIdentifier, providerOverride, complete, resumePolicy);
        }
    }

    private Outcome executeLocked(
            String projectIdentifier,
            String providerOverride,
            boolean forceFull,
            IndexingResumePolicy resumePolicy
    ) throws IOException {
        Preparation prepared = prepare(projectIdentifier, providerOverride, forceFull, true);
        requireReadyRuntimes(prepared);
        if (prepared.effectiveMode() == IndexingMode.NONE && !forceFull) {
            return noChanges(prepared, resumePolicy);
        }
        IndexingRun run = recoverPromotedRunIfNeeded(runLifecycle(prepared, forceFull, resumePolicy));
        if (run.status() != IndexingRun.Status.SUCCEEDED) {
            throw failedRun(prepared, run);
        }
        ProjectFingerprint after = fingerprintService.capture(prepared.project().rootPath());
        boolean fingerprintPromoted = prepared.fingerprintBefore().equals(after);
        String diagnostic = prepared.baselineDiagnostic().orElse(null);
        if (fingerprintPromoted) {
            promoteFingerprint(prepared.project().id(), run.activeSnapshotAfter().orElseThrow(), after);
        } else {
            diagnostic = combineDiagnostics(diagnostic,
                    "workspace changed during indexing; fingerprint baseline was not promoted");
        }
        diagnostic = combineDiagnostics(diagnostic, semanticRefresh.refresh(prepared.project().id()));
        diagnostic = combineDiagnostics(diagnostic, compactAfterSuccess(prepared.project().id()));
        return new Outcome(prepared, Optional.of(run), run.status().name(), run.activeSnapshotAfter(),
                fingerprintPromoted, Optional.ofNullable(diagnostic));
    }

    private static void requireReadyRuntimes(Preparation prepared) {
        for (ProviderRuntimeStatus runtime : prepared.providerRuntimes()) {
            if (runtime.state() != ProviderRuntimeStatus.State.READY) {
                throw new IllegalStateException("provider runtime is not ready: " + runtime.providerId()
                        + (runtime.diagnostics().isEmpty() ? "" : " — " + String.join("; ", runtime.diagnostics())));
            }
        }
    }

    private Outcome noChanges(Preparation prepared, IndexingResumePolicy resumePolicy) {
        if (resumePolicy == IndexingResumePolicy.RESUME_ONLY) {
            throw new IllegalStateException("resume-only indexing refused: no changes to index");
        }
        String semanticDiagnostic = semanticRefresh.refresh(prepared.project().id());
        String retentionDiagnostic = compactAfterSuccess(prepared.project().id());
        return new Outcome(prepared, Optional.empty(), Outcome.NO_CHANGES, prepared.indexState().activeSnapshotId(),
                true, Optional.ofNullable(combineDiagnostics(semanticDiagnostic, retentionDiagnostic)));
    }

    private IndexingRun runLifecycle(Preparation prepared, boolean forceFull, IndexingResumePolicy resumePolicy) {
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
                    resumePolicy);
        }
        return lifecycle.executePlanned(
                        prepared.project().id(),
                        prepared.project().rootPath(),
                        prepared.discovery(),
                        prepared.negotiation(),
                        prepared.plan(),
                        resumePolicy)
                .orElseThrow(() -> new IllegalStateException("planned execution unexpectedly produced no run"));
    }

    private IllegalStateException failedRun(Preparation prepared, IndexingRun run) {
        IllegalStateException failure = new IllegalStateException(failureMessage(run));
        compactAfterFailure(prepared.project().id(), failure);
        return failure;
    }

    /** D3 (b), owner decision 2026-10-21: a fingerprint that cannot be published stays a failure of the command. */
    void promoteFingerprint(UUID projectId, String activeSnapshotId, ProjectFingerprint after) throws IOException {
        try {
            fingerprintStore.publish(projectId, activeSnapshotId, after);
            fingerprintStore.promote(projectId, activeSnapshotId);
        } catch (IOException fingerprintFailure) {
            compactAfterFailure(projectId, fingerprintFailure);
            throw fingerprintFailure;
        }
    }

    /** Releases what the failed run retained; a retention error is attached to the failure, never replaces it. */
    void compactAfterFailure(UUID projectId, Exception failure) {
        try {
            retentionService.compact(projectId);
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
    public static String failureMessage(IndexingRun run) {
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

    /**
     * Retention is maintenance after an index that already succeeded. Its lock is bounded, so it can time out
     * behind a concurrent compaction: that must not turn a successful index into a failure. The diagnostic
     * never carries the cause, which may name a path.
     */
    private String compactAfterSuccess(UUID projectId) {
        try {
            retentionService.compact(projectId);
            return null;
        } catch (IOException _) {
            return "storage retention did not run (its lock was busy or unavailable); it runs after the next indexing";
        }
    }

    private static String combineDiagnostics(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "; " + second;
    }

    /** {@code persist} is false for a dry run: the state is observed (a repair or a first state is computed, never saved). */
    private Preparation prepare(String projectIdentifier, String providerOverride, boolean forceFull, boolean persist)
            throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        // MINOS-AUD-D06: the reference fingerprint is captured BEFORE the discovery, so a structure created between the
        // two is in the post-run fingerprint without being in this one and the baseline is not promoted (ADR 0014).
        ProjectFingerprint current = fingerprintService.capture(project.rootPath());
        ProjectDiscovery discovery = discoveryService.discover(project.rootPath());
        ProjectIndexState indexState = alignedIndexState(project.id(), persist);
        Optional<String> baselineDiagnostic = Optional.empty();
        Optional<ProjectFingerprintSnapshot> baseline;
        ProjectInvalidationAssessment invalidation;
        try {
            baseline = fingerprintStore.loadActive(project.id());
            invalidation = invalidationService.assess(indexState, baseline, current, discovery);
        } catch (IOException _) {
            // D3 (a): the unreadable baseline is declared, never treated as absent in silence; the cause may name a
            // path and is not carried.
            baselineDiagnostic = Optional.of(BASELINE_UNREADABLE_DIAGNOSTIC);
            invalidation = new ProjectInvalidationAssessment(project.id(), indexState.activeSnapshotId(), Optional.empty(),
                    ProjectInvalidationScope.FULL_REQUIRED,
                    List.of(ProjectInvalidationReason.FINGERPRINT_BASELINE_UNREADABLE),
                    Optional.empty(), List.of(), List.of(), List.of());
        }
        IndexerRegistry registry = indexerRegistries.apply(providerOverride);
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
        List<ProviderRuntimeStatus> runtimes = providers.stream().map(runtimeManager::inspect).toList();
        return new Preparation(project, discovery, negotiation, plan, indexState, current, effectiveMode, reasons,
                forceFull ? List.of() : plan.changedFiles(), providers, runtimes, forceFull, baselineDiagnostic);
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
}
