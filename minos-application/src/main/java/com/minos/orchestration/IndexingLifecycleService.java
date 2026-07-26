package com.minos.orchestration;

import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.Status;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState.Availability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Orchestre un run projet complet sans exposer de type fournisseur.
 *
 * <p>Toutes les sélections négociées doivent réussir, être stagées puis promues
 * atomiquement avant que le nouvel identifiant de snapshot devienne actif.</p>
 */
public final class IndexingLifecycleService {

    private final Map<String, IndexerExecutor> executors;
    private final SnapshotStager stager;
    private final SnapshotPromoter promoter;
    private final IndexStateStore stateStore;
    private final Clock clock;
    private final ConcurrentMap<UUID, Object> projectLocks = new ConcurrentHashMap<>();

    public IndexingLifecycleService(
            Collection<IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore
    ) {
        this(executors, stager, promoter, stateStore, Clock.systemUTC());
    }

    IndexingLifecycleService(
            Collection<IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Clock clock
    ) {
        Objects.requireNonNull(executors, "executors");
        this.stager = Objects.requireNonNull(stager, "stager");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.clock = Objects.requireNonNull(clock, "clock");

        Map<String, IndexerExecutor> byId = new LinkedHashMap<>();
        for (IndexerExecutor executor : executors) {
            Objects.requireNonNull(executor, "executor");
            String id = requireText(executor.indexerId(), "executor.indexerId");
            if (byId.putIfAbsent(id, executor) != null) {
                throw new IllegalArgumentException("Duplicate executor for indexer: " + id);
            }
        }
        this.executors = Map.copyOf(byId);
    }

    /**
     * Compatibilité M1 : un appel direct reste une indexation complète.
     */
    public IndexingRun execute(
            UUID projectId,
            Path projectRoot,
            IndexerNegotiationResult negotiation
    ) {
        return executeInternal(projectId, projectRoot, negotiation, IndexingMode.FULL, List.of());
    }

    /**
     * Exécute un plan M7. Un plan NONE ne crée aucun run.
     */
    public Optional<IndexingRun> executePlanned(
            UUID projectId,
            Path projectRoot,
            IndexerNegotiationResult negotiation,
            IncrementalIndexingPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        if (!Objects.requireNonNull(projectId, "projectId").equals(plan.projectId())) {
            throw new IllegalArgumentException("plan belongs to another project");
        }
        validatePlanAgainstNegotiation(plan, negotiation);
        if (plan.mode() == IndexingMode.NONE) {
            return Optional.empty();
        }
        List<String> changedFiles = plan.mode() == IndexingMode.INCREMENTAL
                ? plan.changedFiles()
                : List.of();
        return Optional.of(executeInternal(projectId, projectRoot, negotiation, plan.mode(), changedFiles));
    }

    private IndexingRun executeInternal(
            UUID projectId,
            Path projectRoot,
            IndexerNegotiationResult negotiation,
            IndexingMode mode,
            List<String> changedFiles
    ) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(negotiation, "negotiation");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(changedFiles, "changedFiles");
        if (mode == IndexingMode.NONE) {
            throw new IllegalArgumentException("NONE must not start an indexing run");
        }
        if (!negotiation.complete()) {
            throw new IllegalArgumentException("indexer negotiation must cover every detected language");
        }
        if (negotiation.selections().isEmpty()) {
            throw new IllegalArgumentException("indexer negotiation must contain at least one selection");
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }

        Object projectLock = projectLocks.computeIfAbsent(projectId, ignored -> new Object());
        UUID runId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        ProjectIndexState previousState;
        IndexingRun run;

        synchronized (projectLock) {
            previousState = stateStore.findProjectState(projectId)
                    .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, createdAt));
            if (previousState.availability() == Availability.INDEXING
                    || previousState.availability() == Availability.REFRESHING) {
                throw new IllegalStateException("project already has an indexing run in progress: " + projectId);
            }

            run = runningRun(
                    runId,
                    projectId,
                    createdAt,
                    Phase.PROVIDER_EXECUTION,
                    List.of(),
                    Optional.empty(),
                    previousState.activeSnapshotId(),
                    Optional.of("provider execution started: mode=" + mode)
            );
            stateStore.saveRun(run);
            stateStore.saveProjectState(new ProjectIndexState(
                    projectId,
                    previousState.activeSnapshotId().isPresent()
                            ? Availability.REFRESHING
                            : Availability.INDEXING,
                    previousState.activeSnapshotId(),
                    Optional.of(runId),
                    createdAt,
                    Optional.of("indexing run in progress: mode=" + mode)
            ));
        }

        List<IndexingArtifact> artifacts = new ArrayList<>();
        List<IndexerExecution> executions = new ArrayList<>();
        Optional<String> stagedSnapshotId = Optional.empty();
        Phase phase = Phase.PROVIDER_EXECUTION;

        try {
            for (var selection : negotiation.selections()) {
                String indexerId = selection.indexer().id();
                IndexerExecutor executor = executors.get(indexerId);
                if (executor == null) {
                    throw new IllegalStateException("No runtime executor registered for indexer: " + indexerId);
                }

                IndexingArtifact artifact = Objects.requireNonNull(
                        executor.execute(new IndexingExecutionRequest(
                                runId,
                                projectId,
                                root,
                                selection,
                                mode,
                                changedFiles
                        )),
                        "indexer execution artifact"
                );
                Path artifactPath = validateArtifact(selection, artifact);
                IndexingArtifact normalizedArtifact = new IndexingArtifact(
                        artifact.language(),
                        artifact.indexerId(),
                        artifactPath
                );
                artifacts.add(normalizedArtifact);
                executions.add(new IndexerExecution(
                        artifact.language(),
                        artifact.indexerId(),
                        artifactPath
                ));
                stateStore.saveRun(runningRun(
                        runId,
                        projectId,
                        createdAt,
                        phase,
                        executions,
                        stagedSnapshotId,
                        previousState.activeSnapshotId(),
                        Optional.of("provider artifacts completed: " + executions.size() + ", mode=" + mode)
                ));
            }

            phase = Phase.STAGING;
            stateStore.saveRun(runningRun(
                    runId,
                    projectId,
                    createdAt,
                    phase,
                    executions,
                    stagedSnapshotId,
                    previousState.activeSnapshotId(),
                    Optional.of("staging project snapshot: mode=" + mode)
            ));

            String stagedId = requireText(
                    stager.stage(new IndexSnapshotStageRequest(runId, projectId, artifacts)),
                    "stagedSnapshotId"
            );
            stagedSnapshotId = Optional.of(stagedId);

            phase = Phase.PROMOTION;
            stateStore.saveRun(runningRun(
                    runId,
                    projectId,
                    createdAt,
                    phase,
                    executions,
                    stagedSnapshotId,
                    previousState.activeSnapshotId(),
                    Optional.of("promoting staged snapshot: mode=" + mode)
            ));

            promoter.promote(projectId, runId, stagedId);

            Instant completedAt = clock.instant();
            IndexingRun succeeded = new IndexingRun(
                    runId,
                    projectId,
                    Status.SUCCEEDED,
                    Phase.COMPLETED,
                    createdAt,
                    Optional.of(completedAt),
                    executions,
                    stagedSnapshotId,
                    previousState.activeSnapshotId(),
                    Optional.of(stagedId),
                    Optional.of("indexing run completed and snapshot promoted: mode=" + mode)
            );

            synchronized (projectLock) {
                stateStore.saveRun(succeeded);
                stateStore.saveProjectState(new ProjectIndexState(
                        projectId,
                        Availability.READY,
                        Optional.of(stagedId),
                        Optional.of(runId),
                        completedAt,
                        Optional.of("active snapshot is current: mode=" + mode)
                ));
            }
            return succeeded;
        } catch (Exception exception) {
            Instant completedAt = clock.instant();
            String failureMessage = failureMessage(exception);
            IndexingRun failed = new IndexingRun(
                    runId,
                    projectId,
                    Status.FAILED,
                    phase,
                    createdAt,
                    Optional.of(completedAt),
                    executions,
                    stagedSnapshotId,
                    previousState.activeSnapshotId(),
                    previousState.activeSnapshotId(),
                    Optional.of(failureMessage)
            );

            synchronized (projectLock) {
                stateStore.saveRun(failed);
                stateStore.saveProjectState(new ProjectIndexState(
                        projectId,
                        previousState.activeSnapshotId().isPresent()
                                ? Availability.STALE
                                : Availability.FAILED,
                        previousState.activeSnapshotId(),
                        Optional.of(runId),
                        completedAt,
                        Optional.of(failureMessage)
                ));
            }
            return failed;
        }
    }

    public ProjectIndexState projectState(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return stateStore.findProjectState(projectId)
                .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, clock.instant()));
    }

    public Optional<IndexingRun> findRun(UUID runId) {
        return stateStore.findRun(Objects.requireNonNull(runId, "runId"));
    }

    public List<IndexingRun> listRuns(UUID projectId) {
        return stateStore.listRuns(Objects.requireNonNull(projectId, "projectId"));
    }

    private static void validatePlanAgainstNegotiation(
            IncrementalIndexingPlan plan,
            IndexerNegotiationResult negotiation
    ) {
        Objects.requireNonNull(negotiation, "negotiation");
        TreeSet<String> negotiated = new TreeSet<>();
        for (IndexerNegotiationResult.IndexerSelection selection : negotiation.selections()) {
            negotiated.add(selection.indexer().id());
        }
        if (!List.copyOf(negotiated).equals(plan.selectedIndexerIds())) {
            throw new IllegalArgumentException("plan selections do not match indexer negotiation");
        }
        if (plan.mode() == IndexingMode.INCREMENTAL) {
            for (IndexerNegotiationResult.IndexerSelection selection : negotiation.selections()) {
                if (!selection.indexer().capabilities().contains(IndexerCapability.INCREMENTAL_INDEXING)) {
                    throw new IllegalArgumentException(
                            "incremental plan contains an indexer without INCREMENTAL_INDEXING: "
                                    + selection.indexer().id()
                    );
                }
            }
        }
    }

    private static Path validateArtifact(
            IndexerNegotiationResult.IndexerSelection selection,
            IndexingArtifact artifact
    ) {
        if (artifact.language() != selection.language()) {
            throw new IllegalStateException("executor returned an artifact for an unexpected language");
        }
        if (!artifact.indexerId().equals(selection.indexer().id())) {
            throw new IllegalStateException("executor returned an artifact for an unexpected indexer");
        }
        Path artifactPath = artifact.finalArtifact().toAbsolutePath().normalize();
        if (!Files.exists(artifactPath) || !Files.isReadable(artifactPath)) {
            throw new IllegalStateException("final index artifact is missing or unreadable: " + artifactPath);
        }
        return artifactPath;
    }

    private static IndexingRun runningRun(
            UUID runId,
            UUID projectId,
            Instant createdAt,
            Phase phase,
            List<IndexerExecution> executions,
            Optional<String> stagedSnapshotId,
            Optional<String> activeSnapshotBefore,
            Optional<String> message
    ) {
        return new IndexingRun(
                runId,
                projectId,
                Status.RUNNING,
                phase,
                createdAt,
                Optional.empty(),
                executions,
                stagedSnapshotId,
                activeSnapshotBefore,
                activeSnapshotBefore,
                message
        );
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must not be blank");
        }
        return value;
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
