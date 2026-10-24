package com.minos.orchestration;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.discovery.ProjectDiscovery;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class IndexingLifecycleService {
    private static final String PROJECT_ID = "projectId";

    private final SnapshotPromoter promoter;
    private final IndexStateStore stateStore;
    private final ResumableRunMarkers markers;
    private final Clock clock;
    private final ProjectFingerprintService fingerprints;
    private final IndexingRunExecutor.Collaborators collaborators;
    private final IndexingLifecyclePlanSupport plans = new IndexingLifecyclePlanSupport();

    /**
     * Lifecycle without a marker port or run directory: reserved for tests and in-memory stores.
     * An interrupted run is finalized there without a marker and no resumption is ever possible
     * (the inert port knows no run directory). The composition root must use
     * {@link #IndexingLifecycleService(Collection, SnapshotStager, SnapshotPromoter,
     * IndexStateStore, ResumableRunMarkers)}.
     */
    public IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                                    SnapshotPromoter promoter, IndexStateStore stateStore) {
        this(executors, stager, promoter, stateStore, ResumableRunMarkers.none(), Clock.systemUTC());
    }

    /** Full wiring: the marker port protects interrupted runs from retention (ADR 0039 §5). */
    public IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                                    SnapshotPromoter promoter, IndexStateStore stateStore,
                                    ResumableRunMarkers markers) {
        this(executors, stager, promoter, stateStore, markers, ResumableArtifactPolicy.DEFAULT, Clock.systemUTC());
    }

    /**
     * Orchestration wiring (AUD-PERF-04, D2): {@code fingerprints} is the fingerprint service of the
     * orchestration; it captures the scope fingerprint of the checkpoints and computes it again when a resumption
     * is verified. The other constructors use a default {@link ProjectFingerprintService}.
     */
    public IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                                    SnapshotPromoter promoter, IndexStateStore stateStore,
                                    ResumableRunMarkers markers, ProjectFingerprintService fingerprints) {
        this(executors, stager, promoter, stateStore, markers, ResumableArtifactPolicy.DEFAULT, Clock.systemUTC(),
                fingerprints);
    }

    /** Full wiring with an explicit policy of the indexers whose artifact is reusable. */
    public IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                                    SnapshotPromoter promoter, IndexStateStore stateStore,
                                    ResumableRunMarkers markers, ResumableArtifactPolicy artifactPolicy) {
        this(executors, stager, promoter, stateStore, markers, artifactPolicy, Clock.systemUTC());
    }

    IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                             SnapshotPromoter promoter, IndexStateStore stateStore, Clock clock) {
        this(executors, stager, promoter, stateStore, ResumableRunMarkers.none(), clock);
    }

    IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                             SnapshotPromoter promoter, IndexStateStore stateStore,
                             ResumableRunMarkers markers, Clock clock) {
        this(executors, stager, promoter, stateStore, markers, ResumableArtifactPolicy.DEFAULT, clock);
    }

    IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                             SnapshotPromoter promoter, IndexStateStore stateStore,
                             ResumableRunMarkers markers, ResumableArtifactPolicy artifactPolicy, Clock clock) {
        this(executors, stager, promoter, stateStore, markers, artifactPolicy, clock, new ProjectFingerprintService());
    }

    IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                             SnapshotPromoter promoter, IndexStateStore stateStore,
                             ResumableRunMarkers markers, ResumableArtifactPolicy artifactPolicy, Clock clock,
                             ProjectFingerprintService fingerprints) {
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        Objects.requireNonNull(stager, "stager");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.markers = Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(artifactPolicy, "artifactPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<String, IndexerExecutor> byId = new LinkedHashMap<>();
        for (IndexerExecutor executor : Objects.requireNonNull(executors, "executors")) {
            String id = Objects.requireNonNull(executor, "executor").indexerId();
            if (id == null || id.isBlank()) throw new IllegalStateException("executor.indexerId must not be blank");
            if (byId.putIfAbsent(id, executor) != null) throw new IllegalArgumentException("Duplicate executor for indexer: " + id);
        }
        // The collaborators of a run, checked above, are grouped once and for all.
        this.collaborators = new IndexingRunExecutor.Collaborators(Map.copyOf(byId), stager, promoter, stateStore,
                markers, artifactPolicy, clock, fingerprints);
    }

    /**
     * Extends the same project lifecycle authority across orchestration work that surrounds a
     * structured run, such as fingerprint-baseline publication. Nested lifecycle calls are safe
     * because qualified stores provide owner-thread/project reentrant leases.
     */
    public <T> T withProjectLease(UUID projectId, ProjectLeaseWork<T> work) throws IOException {
        UUID id = Objects.requireNonNull(projectId, PROJECT_ID);
        Objects.requireNonNull(work, "work");
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(id)) {
            return work.execute();
        }
    }

    public IndexingRun execute(UUID id, Path root, IndexerNegotiationResult negotiation) {
        return execute(id, root, negotiation, IndexingResumePolicy.RESUME);
    }

    /** Full run on the root targets; resumption according to {@code policy} (ADR 0039 §6, resume by default). */
    public IndexingRun execute(UUID id, Path root, IndexerNegotiationResult negotiation, IndexingResumePolicy policy) {
        plans.validate(id, root, negotiation);
        return run(id, root, plans.rootTargets(negotiation), IndexingMode.FULL, List.of(), null, policy,
                ModuleAssignmentRule.none(), null);
    }

    public IndexingRun execute(UUID id, Path root, ProjectDiscovery discovery,
                               IndexerNegotiationResult negotiation) {
        return execute(id, root, discovery, negotiation, IndexingResumePolicy.RESUME);
    }

    public IndexingRun execute(UUID id, Path root, ProjectDiscovery discovery,
                               IndexerNegotiationResult negotiation, IndexingResumePolicy policy) {
        return execute(id, root, discovery, negotiation, policy, null);
    }

    /** Like the scoped run, reusing {@code reference} for the root scope when allowed (AUD-PERF-04). */
    public IndexingRun execute(UUID id, Path root, ProjectDiscovery discovery,
                               IndexerNegotiationResult negotiation, IndexingResumePolicy policy,
                               ReferenceFingerprint reference) {
        plans.validate(id, root, negotiation);
        return run(id, root, plans.scopedTargets(root, discovery, negotiation), IndexingMode.FULL, List.of(),
                null, policy, ModuleAssignmentRule.of(id.toString(), discovery), reference);
    }

    public Optional<IndexingRun> executePlanned(UUID id, Path root, IndexerNegotiationResult negotiation,
                                                IncrementalIndexingPlan plan) {
        return executePlanned(id, root, negotiation, plan, IndexingResumePolicy.RESUME);
    }

    public Optional<IndexingRun> executePlanned(UUID id, Path root, IndexerNegotiationResult negotiation,
                                                IncrementalIndexingPlan plan, IndexingResumePolicy policy) {
        plans.validate(id, root, negotiation);
        return planned(id, root, negotiation, plans.rootTargets(negotiation), plan, policy, ModuleAssignmentRule.none(), null);
    }

    public Optional<IndexingRun> executePlanned(UUID id, Path root, ProjectDiscovery discovery,
                                                IndexerNegotiationResult negotiation, IncrementalIndexingPlan plan) {
        return executePlanned(id, root, discovery, negotiation, plan, IndexingResumePolicy.RESUME);
    }

    public Optional<IndexingRun> executePlanned(UUID id, Path root, ProjectDiscovery discovery,
                                                IndexerNegotiationResult negotiation, IncrementalIndexingPlan plan,
                                                IndexingResumePolicy policy) {
        return executePlanned(id, root, discovery, negotiation, plan, policy, null);
    }

    /** Like the scoped planned run, reusing {@code reference} for the root scope when allowed (AUD-PERF-04). */
    public Optional<IndexingRun> executePlanned(UUID id, Path root, ProjectDiscovery discovery,
                                                IndexerNegotiationResult negotiation, IncrementalIndexingPlan plan,
                                                IndexingResumePolicy policy, ReferenceFingerprint reference) {
        plans.validate(id, root, negotiation);
        return planned(id, root, negotiation, plans.scopedTargets(root, discovery, negotiation), plan, policy,
                ModuleAssignmentRule.of(id.toString(), discovery), reference);
    }

    private Optional<IndexingRun> planned(UUID id, Path root, IndexerNegotiationResult negotiation,
                                          List<IndexingExecutionTarget> targets, IncrementalIndexingPlan plan,
                                          IndexingResumePolicy policy, ModuleAssignmentRule moduleAssignment,
                                          ReferenceFingerprint reference) {
        Objects.requireNonNull(plan, "plan");
        if (!id.equals(plan.projectId())) throw new IllegalArgumentException("plan belongs to another project");
        plans.validatePlan(plan, negotiation);
        if (plan.mode() == IndexingMode.NONE) {
            try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(id)) {
                validatePlanStillCurrent(id, plan);
                return Optional.empty();
            }
        }
        return Optional.of(run(id, root, targets, plan.mode(),
                plan.mode() == IndexingMode.INCREMENTAL ? plan.changedFiles() : List.of(), plan, policy,
                moduleAssignment, reference));
    }

    private IndexingRun run(UUID id, Path root, List<IndexingExecutionTarget> targets,
                            IndexingMode mode, List<String> changedFiles, IncrementalIndexingPlan plan,
                            IndexingResumePolicy policy, ModuleAssignmentRule moduleAssignment,
                            ReferenceFingerprint reference) {
        if (targets.isEmpty()) throw new IllegalArgumentException("indexing execution must contain at least one provider scope");
        Objects.requireNonNull(policy, "policy");
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(id)) {
            if (plan != null) validatePlanStillCurrent(id, plan);
            return IndexingRunExecutor.execute(new IndexingRunExecutor.RunRequest(id, root, targets, mode, changedFiles,
                    policy, moduleAssignment,
                    reference == null ? Optional.empty() : reference.reusableRootScopeFingerprint(fingerprints)),
                    collaborators);
        }
    }

    private void validatePlanStillCurrent(UUID projectId, IncrementalIndexingPlan plan) {
        // Every caller reaches this method with the exclusive project lifecycle lease held. Recover
        // abandoned RUNNING/indexing metadata before deciding whether the plan is still current.
        ProjectIndexState current = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId,
                promoter,
                stateStore,
                markers,
                clock.instant(),
                "reconciled from authoritative active snapshot before validating indexing plan");
        Optional<String> plannedAgainst = plan.invalidation().activeIndexSnapshotId();
        if (!current.activeSnapshotId().equals(plannedAgainst)) {
            throw new IllegalStateException(
                    "indexing plan is stale for project " + projectId
                            + ": plannedAgainst=" + plannedAgainst.orElse("<none>")
                            + " current=" + current.activeSnapshotId().orElse("<none>"));
        }
    }

    /**
     * Recovers the project state under the exclusive lifecycle lease. It is a <em>mutation</em>, not a
     * read: it finalizes every run left RUNNING by a dead owner, rewrites the state, and waits for the
     * lease (bounded) if an indexing is in progress. A status read must not call it: it goes through
     * {@code ProjectIndexStateReconciler.observeStatus}, which takes no lease and writes nothing
     * (batch 2, P1). Its only caller is the incremental coordinator, which already holds the lease.
     */
    public ProjectIndexState recoverProjectState(UUID id) {
        UUID projectId = Objects.requireNonNull(id, PROJECT_ID);
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(projectId)) {
            // The caller owns the same exclusive lifecycle authority as an index mutation.
            // Therefore any pre-existing RUNNING run is necessarily abandoned and must be
            // terminalized instead of exposing an indefinitely stale INDEXING/REFRESHING state.
            return AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                    projectId,
                    promoter,
                    stateStore,
                    markers,
                    clock.instant(),
                    "reconciled abandoned lifecycle state during project-state read");
        }
    }

    public Optional<IndexingRun> findRun(UUID id) {
        return stateStore.findRun(Objects.requireNonNull(id, "runId"));
    }

    public List<IndexingRun> listRuns(UUID id) {
        return stateStore.listRuns(Objects.requireNonNull(id, PROJECT_ID));
    }

    @FunctionalInterface
    public interface ProjectLeaseWork<T> {
        T execute() throws IOException;
    }
}
