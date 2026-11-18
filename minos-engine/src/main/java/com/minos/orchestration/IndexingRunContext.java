package com.minos.orchestration;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.ResumeTrace;
import com.minos.orchestration.IndexingRun.Status;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState.Availability;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Mutable state of one indexing run and the ports it runs against; artifacts and executions are always listed in plan
 * order. One instance per run, never shared between threads. The phases of the run ({@link IndexingTargetExecution},
 * {@link IndexingSnapshotPublication}, {@link IndexingRunOutcomes}, {@link IndexingRunResumption}) read and update it.
 */
final class IndexingRunContext {
    final UUID runId;
    final UUID projectId;
    final Instant createdAt;
    final ProjectIndexState previous;
    final Ports ports;
    final Optional<ResumeTrace> trace;
    final boolean resumed;
    final Map<Path, IndexingTargetExecution.CheckpointMaterial> scopeFingerprints = new HashMap<>();
    Optional<String> staged = Optional.empty();
    Phase phase = Phase.PROVIDER_EXECUTION;
    boolean committed;
    boolean durabilityAcknowledgementPending;
    boolean interruptedWhilePersisting;
    int withheldCheckpoints;
    private final List<IndexingExecutionTarget> plan;
    private final Map<IndexingExecutionTarget, IndexingArtifact> artifactsByTarget = new LinkedHashMap<>();
    private final Map<IndexingExecutionTarget, IndexerExecution> executionsByTarget = new LinkedHashMap<>();

    IndexingRunContext(
            Identity identity,
            ProjectIndexState previous,
            List<IndexingExecutionTarget> plan,
            Ports ports,
            Optional<ResumeTrace> trace,
            boolean resumed
    ) {
        this.runId = identity.runId();
        this.projectId = identity.projectId();
        this.createdAt = identity.createdAt();
        this.previous = previous;
        this.plan = List.copyOf(plan).stream().distinct().toList();
        this.ports = ports;
        this.trace = trace;
        this.resumed = resumed;
    }

    void recordTarget(IndexingExecutionTarget target, IndexingArtifact artifact, IndexerExecution execution) {
        artifactsByTarget.put(target, artifact);
        executionsByTarget.put(target, execution);
    }

    int totalTargets() {
        return plan.size();
    }

    List<IndexingArtifact> artifacts() {
        List<IndexingArtifact> artifacts = new ArrayList<>();
        for (IndexingExecutionTarget target : plan) {
            IndexingArtifact artifact = artifactsByTarget.get(target);
            if (artifact != null) artifacts.add(artifact);
        }
        return List.copyOf(artifacts);
    }

    List<IndexerExecution> executions() {
        List<IndexerExecution> executions = new ArrayList<>();
        for (IndexingExecutionTarget target : plan) {
            IndexerExecution execution = executionsByTarget.get(target);
            if (execution != null) executions.add(execution);
        }
        return List.copyOf(executions);
    }

    /**
     * Clears a raised interrupt flag so the next write goes through (a raised flag closes the file channel), and
     * remembers it: the flag is replayed when the terminal outcome is over.
     */
    void clearInterruptFlagForWrite() {
        interruptedWhilePersisting |= Thread.interrupted();
    }

    int checkpointCount() {
        return (int) executionsByTarget.values().stream().filter(execution -> execution.checkpoint().isPresent()).count();
    }

    /**
     * R5: retains the run directory against the retention of every other indexation, this process
     * or another, from before the first provider until the run ends. The marker is the only thing
     * another process can see; without it a concurrent indexation over budget could delete the
     * artifacts this run already produced. A marker that cannot be written only removes the
     * protection, never the run; a resumed run renews the hold its interruption left.
     */
    void holdRunDirectory() {
        try {
            ports.markers().mark(runId);
        } catch (IOException | RuntimeException failure) {
            IndexingRunExecutor.LOGGER.log(System.Logger.Level.WARNING, "MINOS could not hold the run directory of indexing run "
                    + runId + " against retention: " + failure.getClass().getSimpleName());
        }
    }

    void publishInProgress(IndexingMode mode, String message) {
        ports.stateStore().saveRun(running(message));
        ports.stateStore().saveProjectState(new ProjectIndexState(
                projectId,
                previous.activeSnapshotId().isPresent() ? Availability.REFRESHING : Availability.INDEXING,
                previous.activeSnapshotId(),
                Optional.of(runId),
                createdAt,
                Optional.of("indexing run in progress: mode=" + mode)));
    }

    /** The run as it stands while it is running, with this progress message. */
    IndexingRun running(String message) {
        Optional<String> before = previous.activeSnapshotId();
        return new IndexingRun(runId, projectId, Status.RUNNING, phase, createdAt,
                Optional.empty(), executions(), staged, before, before, Optional.of(message),
                IndexingRun.CURRENT_FORMAT_VERSION, trace);
    }

    static String portable(Path path) {
        return path == null ? "" : path.normalize().toString().replace('\\', '/');
    }

    /** Which run this is: its identifier, its project and the instant it was created. */
    record Identity(UUID runId, UUID projectId, Instant createdAt) {
    }

    /** The validated ports of one run, checked in the order of the refusals of {@link IndexingRunExecutor#execute}. */
    record Ports(
            Map<String, IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            ResumableRunMarkers markers,
            ModuleAssignmentRule moduleAssignment,
            Clock clock,
            ProjectFingerprintService fingerprints,
            Optional<String> rootScopeFingerprint
    ) {
        Ports {
            Objects.requireNonNull(fingerprints, "fingerprints");
            Objects.requireNonNull(rootScopeFingerprint, "rootScopeFingerprint");
            Objects.requireNonNull(executors, "executors");
            Objects.requireNonNull(stager, "stager");
            Objects.requireNonNull(promoter, "promoter");
            Objects.requireNonNull(stateStore, "stateStore");
            Objects.requireNonNull(markers, "markers");
            Objects.requireNonNull(moduleAssignment, "moduleAssignment");
            Objects.requireNonNull(clock, "clock");
        }
    }
}
