package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class IndexingLifecycleService {
    private final Map<String, IndexerExecutor> executors;
    private final SnapshotStager stager;
    private final SnapshotPromoter promoter;
    private final IndexStateStore stateStore;
    private final Clock clock;
    private final ProjectIndexLeaseProvider leaseProvider;
    private final ConcurrentMap<UUID, Object> projectLocks = new ConcurrentHashMap<>();
    private final IndexingLifecyclePlanSupport plans = new IndexingLifecyclePlanSupport();

    /**
     * Creates a production lifecycle with an explicit cross-process project lease authority.
     *
     * <p>The lease is owned by this layer so direct application consumers cannot bypass the
     * single-indexing-run invariant by skipping an adapter-level lock.</p>
     */
    public IndexingLifecycleService(
            Collection<IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            ProjectIndexLeaseProvider leaseProvider
    ) {
        this(executors, stager, promoter, stateStore, Clock.systemUTC(), leaseProvider);
    }

    /** Test-only constructor retaining deterministic clock control with an in-process lease. */
    IndexingLifecycleService(Collection<IndexerExecutor> executors, SnapshotStager stager,
                             SnapshotPromoter promoter, IndexStateStore stateStore, Clock clock) {
        this(executors, stager, promoter, stateStore, clock, inProcessLeaseProvider());
    }

    private IndexingLifecycleService(
            Collection<IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Clock clock,
            ProjectIndexLeaseProvider leaseProvider
    ) {
        this.stager = Objects.requireNonNull(stager, "stager");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseProvider = Objects.requireNonNull(leaseProvider, "leaseProvider");
        Map<String, IndexerExecutor> byId = new LinkedHashMap<>();
        for (IndexerExecutor executor : Objects.requireNonNull(executors, "executors")) {
            String id = Objects.requireNonNull(executor, "executor").indexerId();
            if (id == null || id.isBlank()) throw new IllegalStateException("executor.indexerId must not be blank");
            if (byId.putIfAbsent(id, executor) != null) throw new IllegalArgumentException("Duplicate executor for indexer: " + id);
        }
        this.executors = Map.copyOf(byId);
    }

    public IndexingRun execute(UUID id, Path root, IndexerNegotiationResult negotiation) {
        plans.validate(id, root, negotiation);
        return run(id, root, plans.rootTargets(negotiation), IndexingMode.FULL, List.of());
    }

    public IndexingRun execute(UUID id, Path root, ProjectDiscovery discovery,
                               IndexerNegotiationResult negotiation) {
        plans.validate(id, root, negotiation);
        return run(id, root, plans.scopedTargets(root, discovery, negotiation), IndexingMode.FULL, List.of());
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
        if (plan.mode() == IndexingMode.NONE) return Optional.empty();
        return Optional.of(run(id, root, targets, plan.mode(),
                plan.mode() == IndexingMode.INCREMENTAL ? plan.changedFiles() : List.of()));
    }

    private IndexingRun run(UUID id, Path root, List<IndexingExecutionTarget> targets,
                            IndexingMode mode, List<String> changedFiles) {
        if (targets.isEmpty()) throw new IllegalArgumentException("indexing execution must contain at least one provider scope");
        try (ProjectIndexLeaseProvider.Lease ignored = leaseProvider.acquire(id)) {
            return IndexingRunExecutor.execute(id, root, targets, mode, changedFiles,
                    executors, stager, promoter, stateStore, clock, projectLocks);
        }
    }

    public ProjectIndexState projectState(UUID id) {
        return AuthoritativeProjectStateReconciler.reconcile(
                Objects.requireNonNull(id, "projectId"),
                promoter,
                stateStore,
                clock.instant(),
                "reconciled from authoritative active snapshot during project-state read");
    }

    public Optional<IndexingRun> findRun(UUID id) { return stateStore.findRun(Objects.requireNonNull(id, "runId")); }
    public List<IndexingRun> listRuns(UUID id) { return stateStore.listRuns(Objects.requireNonNull(id, "projectId")); }

    private static ProjectIndexLeaseProvider inProcessLeaseProvider() {
        ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
        return projectId -> {
            ReentrantLock lock = locks.computeIfAbsent(Objects.requireNonNull(projectId, "projectId"), ignored -> new ReentrantLock());
            lock.lock();
            return lock::unlock;
        };
    }
}
