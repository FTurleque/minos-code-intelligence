package com.minos.orchestration;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

final class IndexingRunExecutor {
    private IndexingRunExecutor() { }

    static IndexingRun execute(UUID projectId, Path projectRoot, List<IndexingExecutionTarget> targets,
                               IndexingMode mode, List<String> changedFiles,
                               Map<String, IndexerExecutor> executors, SnapshotStager stager,
                               SnapshotPromoter promoter, IndexStateStore stateStore, Clock clock,
                               ConcurrentMap<UUID, Object> projectLocks) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        if (mode == IndexingMode.INCREMENTAL
                && targets.stream().map(IndexingExecutionTarget::projectRelativeRoot).distinct().count() > 1) {
            throw new IllegalArgumentException("multi-scope incremental indexing is not qualified; planner must require FULL for this topology");
        }

        Object lock = projectLocks.computeIfAbsent(projectId, ignored -> new Object());
        UUID runId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        ProjectIndexState previous;
        synchronized (lock) {
            previous = stateStore.findProjectState(projectId)
                    .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, createdAt));
            previous = reconcilePreviousWithAuthoritativeSnapshot(projectId, previous, promoter, stateStore, createdAt);
            if (previous.availability() == Availability.INDEXING || previous.availability() == Availability.REFRESHING) {
                throw new IllegalStateException("project already has an indexing run in progress: " + projectId);
            }
            stateStore.saveRun(running(runId, projectId, createdAt, Phase.PROVIDER_EXECUTION, List.of(),
                    Optional.empty(), previous.activeSnapshotId(),
                    Optional.of("provider execution started: mode=" + mode + ", scopes=" + targets.size())));
            stateStore.saveProjectState(new ProjectIndexState(projectId,
                    previous.activeSnapshotId().isPresent() ? Availability.REFRESHING : Availability.INDEXING,
                    previous.activeSnapshotId(), Optional.of(runId), createdAt,
                    Optional.of("indexing run in progress: mode=" + mode)));
        }

        List<IndexingArtifact> artifacts = new ArrayList<>();
        List<IndexerExecution> executions = new ArrayList<>();
        Optional<String> staged = Optional.empty();
        Phase phase = Phase.PROVIDER_EXECUTION;
        boolean committed = false;
        try {
            for (IndexingExecutionTarget target : targets) {
                var selection = target.selection();
                String indexerId = selection.indexer().id();
                IndexerExecutor executor = executors.get(indexerId);
                if (executor == null) throw new IllegalStateException("No runtime executor registered for indexer: " + indexerId);
                Path relative = target.projectRelativeRoot();
                Path executionRoot = root.resolve(relative).normalize();
                if (!executionRoot.startsWith(root) || !Files.isDirectory(executionRoot)) {
                    throw new IllegalStateException("provider execution root is missing or outside project: " + portable(relative));
                }
                IndexingArtifact artifact = Objects.requireNonNull(executor.execute(new IndexingExecutionRequest(
                        runId, projectId, root, executionRoot, relative, selection, mode,
                        scopedChangedFiles(mode, changedFiles, relative))), "indexer execution artifact");
                Path artifactPath = validateArtifact(selection, artifact, relative);
                artifacts.add(new IndexingArtifact(artifact.language(), artifact.indexerId(), artifactPath, relative));
                executions.add(new IndexerExecution(artifact.language(), artifact.indexerId(), artifactPath));
                stateStore.saveRun(running(runId, projectId, createdAt, phase, executions, staged,
                        previous.activeSnapshotId(), Optional.of("provider artifacts completed: " + executions.size()
                                + "/" + targets.size() + ", mode=" + mode + ", scope=" + portable(relative))));
            }

            phase = Phase.STAGING;
            stateStore.saveRun(running(runId, projectId, createdAt, phase, executions, staged,
                    previous.activeSnapshotId(), Optional.of("staging project snapshot: mode=" + mode)));
            String stagedId = requireText(stager.stage(new IndexSnapshotStageRequest(runId, projectId, artifacts)),
                    "stagedSnapshotId");
            staged = Optional.of(stagedId);
            phase = Phase.PROMOTION;
            stateStore.saveRun(running(runId, projectId, createdAt, phase, executions, staged,
                    previous.activeSnapshotId(), Optional.of("promoting staged snapshot: mode=" + mode)));

            promoter.promote(projectId, runId, stagedId);
            committed = true;
            Instant completedAt = clock.instant();
            IndexingRun succeeded = new IndexingRun(runId, projectId, Status.SUCCEEDED, Phase.COMPLETED,
                    createdAt, Optional.of(completedAt), executions, staged, previous.activeSnapshotId(),
                    Optional.of(stagedId), Optional.of("indexing run completed and snapshot promoted: mode=" + mode
                            + ", scopes=" + targets.size()));
            synchronized (lock) {
                stateStore.saveRun(succeeded);
                stateStore.saveProjectState(new ProjectIndexState(projectId, Availability.READY,
                        Optional.of(stagedId), Optional.of(runId), completedAt,
                        Optional.of("active snapshot is current: mode=" + mode)));
            }
            return succeeded;
        } catch (Exception failure) {
            Instant completedAt = clock.instant();
            String message = failureMessage(failure);
            Optional<String> activeAfter = committed ? staged : previous.activeSnapshotId();
            IndexingRun failed = new IndexingRun(runId, projectId, Status.FAILED, phase, createdAt,
                    Optional.of(completedAt), executions, staged, previous.activeSnapshotId(), activeAfter,
                    Optional.of(committed ? "snapshot promotion committed; metadata finalization failed: " + message : message));
            synchronized (lock) {
                if (committed) {
                    persist(() -> stateStore.saveProjectState(new ProjectIndexState(projectId, Availability.READY,
                            activeAfter, Optional.of(runId), completedAt,
                            Optional.of("active snapshot committed; run metadata recovery required: " + message))), failure);
                    persist(() -> stateStore.saveRun(failed), failure);
                } else {
                    persist(() -> stateStore.saveRun(failed), failure);
                    persist(() -> stateStore.saveProjectState(new ProjectIndexState(projectId,
                            previous.activeSnapshotId().isPresent() ? Availability.STALE : Availability.FAILED,
                            previous.activeSnapshotId(), Optional.of(runId), completedAt, Optional.of(message))), failure);
                }
            }
            return failed;
        }
    }

    private static ProjectIndexState reconcilePreviousWithAuthoritativeSnapshot(
            UUID projectId,
            ProjectIndexState previous,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant now
    ) {
        try {
            Optional<String> authoritative = promoter.activeSnapshotId(projectId);
            if (authoritative.isEmpty() || authoritative.equals(previous.activeSnapshotId())) return previous;
            ProjectIndexState repaired = new ProjectIndexState(projectId, Availability.READY, authoritative,
                    Optional.empty(), now,
                    Optional.of("reconciled from authoritative active snapshot before new indexing run"));
            stateStore.saveProjectState(repaired);
            ProjectIndexState verified = stateStore.findProjectState(projectId)
                    .orElseThrow(() -> new IllegalStateException("reconciled project state was not persisted"));
            if (!authoritative.equals(verified.activeSnapshotId()) || verified.availability() != Availability.READY) {
                throw new IllegalStateException("reconciled project state does not match authoritative snapshot");
            }
            return verified;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("unable to reconcile project state before indexing", failure);
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
}
