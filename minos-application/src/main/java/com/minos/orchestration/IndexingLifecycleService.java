package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.incremental.IncrementalIndexingPlan;
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

    private final Map<String, IndexerExecutor> executors;
    private final SnapshotStager stager;
    private final SnapshotPromoter promoter;
    private final IndexStateStore stateStore;
    private final Clock clock;
    private final IndexingLifecyclePlanSupport plans = new IndexingLifecyclePlanSupport();

    public IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                                    SnapshotPromoter promoter, IndexStateStore stateStore) {
        this(executors, stager, promoter, stateStore, Clock.systemUTC());
    }

    IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                             SnapshotPromoter promoter, IndexStateStore stateStore, Clock clock) {
        this.stager = Objects.requireNonNull(stager, "stager");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<String, IndexerExecutor> byId = new LinkedHashMap<>();
        for (IndexerExecutor executor : Objects.requireNonNull(executors, "executors")) {
            String id = Objects.requireNonNull(executor, "executor").indexerId();
            if (id == null || id.isBlank()) throw new IllegalStateException("executor.indexerId must not be blank");
            if (byId.putIfAbsent(id, executor) != null) throw new IllegalArgumentException("Duplicate executor for indexer: " + id);
        }
        this.executors = Map.copyOf(byId);
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
        plans.validate(id, root, negotiation);
        return run(id, root, plans.rootTargets(negotiation), IndexingMode.FULL, List.of(), null);
    }

    public IndexingRun execute(UUID id, Path root, ProjectDiscovery discovery,
                               IndexerNegotiationResult negotiation) {
        plans.validate(id, root, negotiation);
        return run(id, root, plans.scopedTargets(root, discovery, negotiation), IndexingMode.FULL, List.of(), null);
    }

    public Optional<IndexingRun> executePlanned(UUID id, Path root, IndexerNegotiationResult negotiation,
                                                IncrementalIndexingPlan plan) {
        plans.validate(id, root, negotiation);
        return planned(id, root, negotiation, plans.rootTargets(negotiation), plan);
    }

    public Optional<IndexingRun> executePlanned(UUID id, Path root, ProjectDiscovery discovery,
                                                IndexerNegotiationResult negotiation, IncrementalIndexingPlan plan) {
        plans.validate(id, root, negotiation);
        return planned(id, root, negotiation, plans.scopedTargets(root, discovery, negotiation), plan);
    }

    private Optional<IndexingRun> planned(UUID id, Path root, IndexerNegotiationResult negotiation,
                                          List<IndexingExecutionTarget> targets, IncrementalIndexingPlan plan) {
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
                plan.mode() == IndexingMode.INCREMENTAL ? plan.changedFiles() : List.of(), plan));
    }

    private IndexingRun run(UUID id, Path root, List<IndexingExecutionTarget> targets,
                            IndexingMode mode, List<String> changedFiles, IncrementalIndexingPlan plan) {
        if (targets.isEmpty()) throw new IllegalArgumentException("indexing execution must contain at least one provider scope");
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(id)) {
            if (plan != null) validatePlanStillCurrent(id, plan);
            return IndexingRunExecutor.execute(id, root, targets, mode, changedFiles,
                    executors, stager, promoter, stateStore, clock);
        }
    }

    private void validatePlanStillCurrent(UUID projectId, IncrementalIndexingPlan plan) {
        // Every caller reaches this method with the exclusive project lifecycle lease held. Recover
        // abandoned RUNNING/indexing metadata before deciding whether the plan is still current.
        ProjectIndexState current = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId,
                promoter,
                stateStore,
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

    public ProjectIndexState projectState(UUID id) {
        UUID projectId = Objects.requireNonNull(id, PROJECT_ID);
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(projectId)) {
            // The read already owns the same exclusive lifecycle authority as an index mutation.
            // Therefore any pre-existing RUNNING run is necessarily abandoned and must be
            // terminalized instead of exposing an indefinitely stale INDEXING/REFRESHING state.
            return AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                    projectId,
                    promoter,
                    stateStore,
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
