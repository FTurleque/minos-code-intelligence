package com.minos.orchestration;

import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resume plan of an {@code INTERRUPTED} run (ADR 0039 §3 and §7).
 *
 * <p>The plan always starts from the targets negotiated <em>now</em>, never from the list of
 * executions of the interrupted run. For each target, the checkpoint with the same key
 * ({@link IndexingRun#targetKey}) is kept only if all the conditions of the ADR hold.
 * A target without a valid checkpoint for an expected reason (indexer not qualified, different mode
 * or targeted files, modified sources of the scope) is simply run again; any unexpected gap
 * (missing artifact, outside the run directory, symbolic link, different size or SHA-256,
 * fingerprint that cannot be computed, TTL exceeded) cancels the whole resumption. Fail-closed: at
 * the slightest doubt, full run.</p>
 */
final class IndexingResumePlanner {

    static final Duration DEFAULT_RESUME_TTL = Duration.ofHours(24);

    private static final System.Logger LOGGER = System.getLogger(IndexingResumePlanner.class.getName());

    private IndexingResumePlanner() {
    }

    record Request(
            ProjectIndexState previous,
            Path projectRoot,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode,
            List<String> changedFiles,
            Instant now,
            Duration resumeTtl,
            ProjectFingerprintService fingerprints
    ) {
        Request {
            Objects.requireNonNull(previous, "previous");
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            Objects.requireNonNull(mode, "mode");
            changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(resumeTtl, "resumeTtl");
            Objects.requireNonNull(fingerprints, "fingerprints");
            if (resumeTtl.isZero() || resumeTtl.isNegative()) {
                throw new IllegalArgumentException("resumeTtl must be positive");
            }
        }
    }

    /** Target of the current plan whose artifact from the interrupted run is reused as is. */
    record ReusedTarget(IndexingExecutionTarget target, IndexerExecution execution) {
        ReusedTarget {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(execution, "execution");
            if (execution.checkpoint().isEmpty()) {
                throw new IllegalArgumentException("a reused target requires a checkpoint");
            }
        }
    }

    sealed interface Outcome permits Outcome.Resume, Outcome.Refused, Outcome.NotOffered {
        /** The run is reopened: {@code reused} as is, {@code remaining} run again. */
        record Resume(IndexingRun run, int attempt, List<ReusedTarget> reused, List<IndexingExecutionTarget> remaining)
                implements Outcome {
            public Resume {
                Objects.requireNonNull(run, "run");
                if (attempt < 2) throw new IllegalArgumentException("a resumed attempt is at least the second one");
                reused = List.copyOf(Objects.requireNonNull(reused, "reused"));
                remaining = List.copyOf(Objects.requireNonNull(remaining, "remaining"));
                if (reused.isEmpty()) throw new IllegalArgumentException("a resume reuses at least one target");
            }

            /** True when the interrupted attempt had already staged a snapshot, in the promotion phase. */
            boolean stagedSnapshotKnown() {
                return run.phase() == IndexingRun.Phase.PROMOTION && run.stagedSnapshotId().isPresent();
            }

            /**
             * True only when the resumption covers <em>exactly</em> what the interrupted run had put in
             * the snapshot: no target to run again, and the set of target keys of the run's executions
             * equal to the set of current targets (R4). The snapshot staged by the interrupted attempt
             * contains the index of each of its executions; if a module has disappeared since, promoting it
             * would publish an index that a full run would never produce. Fail-closed: an execution without
             * a checkpoint has no target key, so it is not proven present in the plan.
             */
            boolean stagedSnapshotCoversThePlan() {
                if (!remaining.isEmpty()) return false;
                Set<String> staged = new HashSet<>();
                for (IndexerExecution execution : run.executions()) {
                    Optional<String> key = executionKey(execution);
                    if (key.isEmpty()) return false;
                    staged.add(key.orElseThrow());
                }
                Set<String> current = new HashSet<>();
                for (ReusedTarget target : reused) current.add(targetKey(target.target()));
                return staged.equals(current);
            }
        }

        /** A run was offered but the resumption is refused; {@code reason} is public (no path). */
        record Refused(String reason) implements Outcome {
            public Refused {
                if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            }
        }

        /** The project offers no run for resumption. */
        record NotOffered() implements Outcome {
        }
    }

    static Outcome plan(
            IndexStateStore stateStore,
            ResumableRunMarkers port,
            ResumableArtifactPolicy policy,
            Request request
    ) {
        Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        Optional<UUID> reference = request.previous().resumableRunId();
        if (reference.isEmpty()) return new Outcome.NotOffered();
        UUID runId = reference.orElseThrow();
        try {
            return decide(stateStore, port, policy, request, runId);
        } catch (Refusal refusal) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS refuses to resume indexing run " + runId + " and falls back to a full run: "
                            + refusal.getMessage());
            return new Outcome.Refused(refusal.getMessage());
        }
    }

    private static Outcome decide(
            IndexStateStore stateStore,
            ResumableRunMarkers port,
            ResumableArtifactPolicy policy,
            Request request,
            UUID runId
    ) throws Refusal {
        IndexingRun run = stateStore.findRun(runId)
                .filter(candidate -> candidate.projectId().equals(request.previous().projectId()))
                .filter(candidate -> candidate.status() == IndexingRun.Status.INTERRUPTED)
                .orElseThrow(() -> new Refusal("resumable run is missing or not interrupted"));
        if (run.runFormatVersion() != IndexingRun.CURRENT_FORMAT_VERSION) {
            throw new Refusal("resumable run was written in run format " + run.runFormatVersion()
                    + ", expected " + IndexingRun.CURRENT_FORMAT_VERSION);
        }
        Path runDirectory = existingRunDirectory(port, runId);

        List<ReusedTarget> reused = new ArrayList<>();
        List<IndexingExecutionTarget> remaining = new ArrayList<>();
        for (IndexingExecutionTarget target : request.targets()) {
            Optional<IndexerExecution> checkpointed = checkpointFor(run, target);
            if (checkpointed.isEmpty() || !reusable(target, checkpointed.orElseThrow(), request, policy, runDirectory)) {
                remaining.add(target);
            } else {
                reused.add(new ReusedTarget(target, checkpointed.orElseThrow()));
            }
        }
        if (reused.isEmpty()) throw new Refusal("no reusable target in the interrupted run");
        int attempt = run.resume().map(IndexingRun.ResumeTrace::attempt).orElse(1) + 1;
        return new Outcome.Resume(run, attempt, reused, remaining);
    }

    /**
     * The run directory as the port names it: {@link ArtifactConfinement} resolves it itself, and reads an
     * artifact path written the same way as the directory (a short Windows name, say) without a second guess.
     */
    private static Path existingRunDirectory(ResumableRunMarkers port, UUID runId) throws Refusal {
        Path runDirectory = port.runDirectory(runId)
                .orElseThrow(() -> new Refusal("run directory is unknown to this runtime"));
        if (!Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refusal("run directory is missing");
        }
        return runDirectory;
    }

    private static Optional<IndexerExecution> checkpointFor(IndexingRun run, IndexingExecutionTarget target) {
        String key = targetKey(target);
        return run.executions().stream()
                .filter(execution -> executionKey(execution).filter(key::equals).isPresent())
                .findFirst();
    }

    /** Key of the current target: the one that the checkpoint of an execution carried if it succeeded. */
    private static String targetKey(IndexingExecutionTarget target) {
        IndexerSelection selection = target.selection();
        return IndexingRun.targetKey(
                selection.indexer().id(), selection.indexer().version(), target.projectRelativeRoot());
    }

    /** Target key of an execution of the run, empty when it carries no checkpoint. */
    private static Optional<String> executionKey(IndexerExecution execution) {
        return execution.checkpoint().map(checkpoint -> IndexingRun.targetKey(
                execution.indexerId(), checkpoint.providerVersion(), checkpoint.projectRelativeRoot()));
    }

    /**
     * Whether a checkpointed execution may serve the target. Returns {@code false} for the expected
     * reasons (the target is simply re-executed) and throws {@link Refusal} for every unexpected
     * discrepancy (the whole resume is abandoned).
     */
    private static boolean reusable(
            IndexingExecutionTarget target,
            IndexerExecution execution,
            Request request,
            ResumableArtifactPolicy policy,
            Path runDirectory
    ) throws Refusal {
        IndexerSelection selection = target.selection();
        ExecutionCheckpoint checkpoint = execution.checkpoint().orElseThrow();
        if (!policy.qualifies(selection.indexer())) return false;
        if (execution.language() != selection.language()) return false;
        if (checkpoint.mode() != request.mode()) return false;
        List<String> scoped = IndexingRunExecutor.scopedChangedFiles(
                request.mode(), request.changedFiles(), target.projectRelativeRoot());
        if (!checkpoint.changedFiles().equals(scoped)) return false;
        if (Duration.between(checkpoint.completedAt(), request.now()).compareTo(request.resumeTtl()) > 0) {
            throw new Refusal("checkpoint older than resume TTL");
        }

        Path artifact = execution.finalArtifact();
        // Containment is decided, physically, before a single byte is read (R1-6): the one decision of
        // the orchestration, shared with the executor's validation and the reverification (Q5).
        try {
            ArtifactConfinement.requireInside(runDirectory, artifact);
        } catch (ArtifactConfinement.Escape escape) {
            throw new Refusal(escape.getMessage());
        }
        long size;
        try {
            size = Files.size(artifact);
        } catch (IOException failure) {
            throw new Refusal("artifact size cannot be read: " + failure.getClass().getSimpleName());
        }
        if (size != checkpoint.artifactBytes() || size > IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
            throw new Refusal("artifact size differs from checkpoint");
        }
        ExecutionCheckpoints.ArtifactDigest digest;
        try {
            digest = ExecutionCheckpoints.artifactDigest(artifact);
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            throw new Refusal(unavailable.getMessage());
        }
        if (digest.bytes() != checkpoint.artifactBytes() || !digest.sha256().equals(checkpoint.artifactSha256())) {
            throw new Refusal("artifact digest differs from checkpoint");
        }

        String fingerprint;
        try {
            fingerprint = ExecutionCheckpoints.scopeFingerprint(
                    request.fingerprints(), request.projectRoot(), target.projectRelativeRoot());
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            throw new Refusal(unavailable.getMessage());
        }
        return fingerprint.equals(checkpoint.scopeFingerprint());
    }

    private static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;

        private Refusal(String reason) {
            super(reason);
        }
    }
}
