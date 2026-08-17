package com.minos.orchestration;

import com.minos.io.CommitUncertainException;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.Status;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState.Availability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class IndexingRunExecutor {
    private IndexingRunExecutor() { }

    static IndexingRun execute(UUID projectId, Path projectRoot, List<IndexingExecutionTarget> targets,
                               IndexingMode mode, List<String> changedFiles,
                               Map<String, IndexerExecutor> executors, SnapshotStager stager,
                               SnapshotPromoter promoter, IndexStateStore stateStore, Clock clock) {
        Path root = validateExecutionRoot(projectRoot, targets, mode);
        Instant createdAt = clock.instant();
        ProjectIndexState previous = reconcilePreviousState(projectId, promoter, stateStore, createdAt);
        RunContext context = new RunContext(UUID.randomUUID(), projectId, createdAt, previous, targets.size());

        try {
            publishInProgress(context, mode, stateStore);
            executeProviders(context, root, targets, mode, changedFiles, executors, stateStore);
            stageSnapshot(context, mode, stager, stateStore);
            promoteSnapshot(context, promoter);
            return persistSuccess(context, mode, stateStore, clock.instant());
        } catch (Exception failure) {
            return persistFailure(context, failure, stateStore, clock.instant());
        }
    }

    private static Path validateExecutionRoot(
            Path projectRoot,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode
    ) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }
        long scopes = targets.stream().map(IndexingExecutionTarget::projectRelativeRoot).distinct().count();
        if (mode == IndexingMode.INCREMENTAL && scopes > 1) {
            throw new IllegalArgumentException(
                    "multi-scope incremental indexing is not qualified; planner must require FULL for this topology");
        }
        try {
            return root.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "projectRoot could not be resolved to a canonical directory: " + projectRoot, failure);
        }
    }

    private static ProjectIndexState reconcilePreviousState(
            UUID projectId,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant createdAt
    ) {
        ProjectIndexState previous = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId,
                promoter,
                stateStore,
                createdAt,
                "reconciled from authoritative active snapshot before new indexing run");
        if (previous.availability() == Availability.INDEXING
                || previous.availability() == Availability.REFRESHING) {
            throw new IllegalStateException("project already has an indexing run in progress: " + projectId);
        }
        return previous;
    }

    private static void publishInProgress(
            RunContext context,
            IndexingMode mode,
            IndexStateStore stateStore
    ) {
        stateStore.saveRun(running(
                context.runId,
                context.projectId,
                context.createdAt,
                Phase.PROVIDER_EXECUTION,
                List.of(),
                Optional.empty(),
                context.previous.activeSnapshotId(),
                Optional.of("provider execution started: mode=" + mode + ", scopes=" + context.totalTargets)));
        stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                context.previous.activeSnapshotId().isPresent() ? Availability.REFRESHING : Availability.INDEXING,
                context.previous.activeSnapshotId(),
                Optional.of(context.runId),
                context.createdAt,
                Optional.of("indexing run in progress: mode=" + mode)));
    }

    private static void executeProviders(
            RunContext context,
            Path root,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode,
            List<String> changedFiles,
            Map<String, IndexerExecutor> executors,
            IndexStateStore stateStore
    ) throws Exception {
        for (IndexingExecutionTarget target : targets) {
            executeProvider(context, root, target, mode, changedFiles, executors, stateStore);
        }
    }

    private static void executeProvider(
            RunContext context,
            Path root,
            IndexingExecutionTarget target,
            IndexingMode mode,
            List<String> changedFiles,
            Map<String, IndexerExecutor> executors,
            IndexStateStore stateStore
    ) throws Exception {
        var selection = target.selection();
        String indexerId = selection.indexer().id();
        IndexerExecutor executor = requireExecutor(executors, indexerId);
        Path relative = target.projectRelativeRoot();
        Path executionRoot = requireExecutionRoot(root, relative);
        IndexingArtifact artifact = Objects.requireNonNull(executor.execute(new IndexingExecutionRequest(
                context.runId,
                context.projectId,
                root,
                executionRoot,
                relative,
                selection,
                mode,
                scopedChangedFiles(mode, changedFiles, relative))), "indexer execution artifact");
        Path artifactPath = validateArtifact(selection, artifact, relative);
        context.artifacts.add(new IndexingArtifact(
                artifact.language(), artifact.indexerId(), artifactPath, relative));
        context.executions.add(new IndexerExecution(artifact.language(), artifact.indexerId(), artifactPath));
        stateStore.saveRun(running(
                context.runId,
                context.projectId,
                context.createdAt,
                context.phase,
                context.executions,
                context.staged,
                context.previous.activeSnapshotId(),
                Optional.of("provider artifacts completed: " + context.executions.size()
                        + "/" + context.totalTargets + ", mode=" + mode + ", scope=" + portable(relative))));
    }

    private static IndexerExecutor requireExecutor(Map<String, IndexerExecutor> executors, String indexerId) {
        IndexerExecutor executor = executors.get(indexerId);
        if (executor == null) {
            throw new IllegalStateException("No runtime executor registered for indexer: " + indexerId);
        }
        return executor;
    }

    private static Path requireExecutionRoot(Path root, Path relative) {
        Path executionRoot = root.resolve(relative).normalize();
        if (!executionRoot.startsWith(root) || !Files.isDirectory(executionRoot)) {
            throw new IllegalStateException(
                    "provider execution root is missing or outside project: " + portable(relative));
        }
        try {
            Path realExecutionRoot = executionRoot.toRealPath();
            if (!realExecutionRoot.startsWith(root)) {
                throw new IllegalStateException(
                        "provider execution root resolves outside project: " + portable(relative));
            }
            // Security is decided on canonical paths, but the execution request deliberately keeps
            // the lexical root so its registeredRoot + relativeRoot contract remains stable. The
            // sandbox backend canonicalizes the validated mount before applying OS isolation.
            return executionRoot;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "provider execution root could not be resolved safely: " + portable(relative), failure);
        }
    }

    private static void stageSnapshot(
            RunContext context,
            IndexingMode mode,
            SnapshotStager stager,
            IndexStateStore stateStore
    ) throws Exception {
        context.phase = Phase.STAGING;
        stateStore.saveRun(running(
                context.runId,
                context.projectId,
                context.createdAt,
                context.phase,
                context.executions,
                context.staged,
                context.previous.activeSnapshotId(),
                Optional.of("staging project snapshot: mode=" + mode)));
        String stagedId = requireText(stager.stage(new IndexSnapshotStageRequest(
                context.runId, context.projectId, context.artifacts)), "stagedSnapshotId");
        context.staged = Optional.of(stagedId);
        context.phase = Phase.PROMOTION;
        stateStore.saveRun(running(
                context.runId,
                context.projectId,
                context.createdAt,
                context.phase,
                context.executions,
                context.staged,
                context.previous.activeSnapshotId(),
                Optional.of("promoting staged snapshot: mode=" + mode)));
    }

    private static void promoteSnapshot(RunContext context, SnapshotPromoter promoter) throws Exception {
        String stagedId = context.staged.orElseThrow();
        try {
            promoter.promote(context.projectId, context.runId, stagedId);
            context.committed = true;
        } catch (CommitUncertainException uncertain) {
            recoverUncertainPromotion(context, promoter, stagedId, uncertain);
        }
    }

    private static void recoverUncertainPromotion(
            RunContext context,
            SnapshotPromoter promoter,
            String stagedId,
            CommitUncertainException uncertain
    ) throws CommitUncertainException {
        if (!authoritativeTargetIsActive(promoter, context.projectId, stagedId, uncertain)) throw uncertain;
        context.committed = true;
        context.durabilityAcknowledgementPending = true;
    }

    private static IndexingRun persistSuccess(
            RunContext context,
            IndexingMode mode,
            IndexStateStore stateStore,
            Instant completedAt
    ) {
        String stagedId = context.staged.orElseThrow();
        String durabilitySuffix = context.durabilityAcknowledgementPending
                ? "; authoritative snapshot confirmed after lost durability acknowledgement"
                : "";
        String successMessage = "indexing run completed and snapshot promoted: mode=" + mode
                + ", scopes=" + context.totalTargets + durabilitySuffix;
        IndexingRun succeeded = new IndexingRun(
                context.runId,
                context.projectId,
                Status.SUCCEEDED,
                Phase.COMPLETED,
                context.createdAt,
                Optional.of(completedAt),
                context.executions,
                context.staged,
                context.previous.activeSnapshotId(),
                Optional.of(stagedId),
                Optional.of(successMessage));
        stateStore.saveRun(succeeded);
        stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                Availability.READY,
                Optional.of(stagedId),
                Optional.of(context.runId),
                completedAt,
                Optional.of("active snapshot is current: mode=" + mode
                        + (context.durabilityAcknowledgementPending
                        ? "; durability acknowledgement pending" : ""))));
        return succeeded;
    }

    private static IndexingRun persistFailure(
            RunContext context,
            Exception failure,
            IndexStateStore stateStore,
            Instant completedAt
    ) {
        String message = failureMessage(failure);
        Optional<String> activeAfter = context.committed ? context.staged : context.previous.activeSnapshotId();
        IndexingRun failed = failedRun(context, completedAt, activeAfter, message);
        if (context.committed) {
            persistCommittedFailure(context, failed, activeAfter, message, stateStore, completedAt, failure);
        } else {
            persistUncommittedFailure(context, failed, message, stateStore, completedAt, failure);
        }
        return failed;
    }

    private static IndexingRun failedRun(
            RunContext context,
            Instant completedAt,
            Optional<String> activeAfter,
            String message
    ) {
        String committedPrefix = context.durabilityAcknowledgementPending
                ? "snapshot promotion is authoritative after lost durability acknowledgement; metadata finalization failed: "
                : "snapshot promotion committed; metadata finalization failed: ";
        return new IndexingRun(
                context.runId,
                context.projectId,
                Status.FAILED,
                context.phase,
                context.createdAt,
                Optional.of(completedAt),
                context.executions,
                context.staged,
                context.previous.activeSnapshotId(),
                activeAfter,
                Optional.of(context.committed ? committedPrefix + message : message));
    }

    private static void persistCommittedFailure(
            RunContext context,
            IndexingRun failed,
            Optional<String> activeAfter,
            String message,
            IndexStateStore stateStore,
            Instant completedAt,
            Exception original
    ) {
        persist(() -> stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                Availability.READY,
                activeAfter,
                Optional.of(context.runId),
                completedAt,
                Optional.of("active snapshot committed; run metadata recovery required: " + message))), original);
        persist(() -> stateStore.saveRun(failed), original);
    }

    private static void persistUncommittedFailure(
            RunContext context,
            IndexingRun failed,
            String message,
            IndexStateStore stateStore,
            Instant completedAt,
            Exception original
    ) {
        persist(() -> stateStore.saveRun(failed), original);
        Availability availability = context.previous.activeSnapshotId().isPresent()
                ? Availability.STALE
                : Availability.FAILED;
        persist(() -> stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                availability,
                context.previous.activeSnapshotId(),
                Optional.of(context.runId),
                completedAt,
                Optional.of(message))), original);
    }

    private static boolean authoritativeTargetIsActive(
            SnapshotPromoter promoter,
            UUID projectId,
            String stagedId,
            CommitUncertainException uncertain
    ) {
        try {
            ActiveSnapshotObservation observation = promoter.observeActiveSnapshot(projectId);
            return observation.status() == ActiveSnapshotObservation.Status.ACTIVE
                    && observation.snapshotId().filter(stagedId::equals).isPresent();
        } catch (Exception observationFailure) {
            uncertain.addSuppressed(observationFailure);
            return false;
        }
    }

    private static List<String> scopedChangedFiles(IndexingMode mode, List<String> changedFiles, Path relative) {
        if (mode != IndexingMode.INCREMENTAL || relative.toString().isEmpty()) return changedFiles;
        String prefix = portable(relative) + "/";
        return changedFiles.stream().filter(path -> path.startsWith(prefix))
                .map(path -> path.substring(prefix.length())).sorted().toList();
    }

    private static Path validateArtifact(IndexerNegotiationResult.IndexerSelection selection,
                                         IndexingArtifact artifact, Path expectedRoot) {
        if (artifact.language() != selection.language()) throw new IllegalStateException("executor returned an artifact for an unexpected language");
        if (!artifact.indexerId().equals(selection.indexer().id())) throw new IllegalStateException("executor returned an artifact for an unexpected indexer");
        if (!artifact.projectRelativeRoot().normalize().equals(expectedRoot.normalize())) throw new IllegalStateException("executor returned an artifact for an unexpected project scope");
        Path path = artifact.finalArtifact().toAbsolutePath().normalize();
        if (!Files.exists(path) || !Files.isReadable(path)) throw new IllegalStateException("final index artifact is missing or unreadable: " + path);
        return path;
    }

    private static IndexingRun running(UUID runId, UUID projectId, Instant createdAt, Phase phase,
                                       List<IndexerExecution> executions, Optional<String> staged,
                                       Optional<String> before, Optional<String> message) {
        return new IndexingRun(runId, projectId, Status.RUNNING, phase, createdAt, Optional.empty(), executions,
                staged, before, before, message);
    }

    private static void persist(Runnable action, Exception original) {
        try { action.run(); } catch (RuntimeException failure) { original.addSuppressed(failure); }
    }

    private static String portable(Path path) { return path == null ? "" : path.normalize().toString().replace('\\', '/'); }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + " must not be blank");
        return value;
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static final class RunContext {
        private final UUID runId;
        private final UUID projectId;
        private final Instant createdAt;
        private final ProjectIndexState previous;
        private final int totalTargets;
        private final List<IndexingArtifact> artifacts = new ArrayList<>();
        private final List<IndexerExecution> executions = new ArrayList<>();
        private Optional<String> staged = Optional.empty();
        private Phase phase = Phase.PROVIDER_EXECUTION;
        private boolean committed;
        private boolean durabilityAcknowledgementPending;

        private RunContext(
                UUID runId,
                UUID projectId,
                Instant createdAt,
                ProjectIndexState previous,
                int totalTargets
        ) {
            this.runId = runId;
            this.projectId = projectId;
            this.createdAt = createdAt;
            this.previous = previous;
            this.totalTargets = totalTargets;
        }
    }
}
