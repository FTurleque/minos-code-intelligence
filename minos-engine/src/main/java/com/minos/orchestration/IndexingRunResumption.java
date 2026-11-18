package com.minos.orchestration;

import com.minos.io.CommitUncertainException;
import com.minos.orchestration.IndexingResumePlanner.Outcome;
import com.minos.orchestration.IndexingResumePlanner.ReusedTarget;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.ResumeTrace;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingTargetExecution.ValidatedProjectRoot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.minos.orchestration.IndexingRunContext.portable;

/**
 * Reopens an interrupted run (ADR 0039): its reused targets are injected without launching their provider, the
 * others are run, then each reused artifact is verified again just before snapshot staging. One instance per
 * resumed attempt.
 */
final class IndexingRunResumption {
    private final Outcome.Resume resume;
    private final IndexingRunContext context;
    private final IndexingTargetExecution targets;
    private final IndexingSnapshotPublication publication;
    private final IndexingRunOutcomes outcomes;

    private IndexingRunResumption(Outcome.Resume resume, IndexingRunContext context, ValidatedProjectRoot root) {
        this.resume = resume;
        this.context = context;
        this.targets = new IndexingTargetExecution(context, root);
        this.publication = new IndexingSnapshotPublication(context, targets);
        this.outcomes = new IndexingRunOutcomes(context);
    }

    /**
     * Runs the resumed attempt. A verification that fails cancels the resumption (run FAILED) and the caller falls
     * back to a full run.
     */
    static ResumedAttempt attempt(
            Outcome.Resume resume,
            ValidatedProjectRoot root,
            List<IndexingExecutionTarget> plan,
            IndexingMode mode,
            List<String> changedFiles,
            ProjectIndexState previous,
            IndexingRunContext.Ports ports
    ) {
        IndexingRun run = resume.run();
        ResumeTrace trace = new ResumeTrace(
                resume.attempt(), resume.reused().size(), resume.remaining().size(), Optional.empty());
        IndexingRunContext context = new IndexingRunContext(
                new IndexingRunContext.Identity(run.id(), run.projectId(), run.createdAt()), previous, plan, ports,
                Optional.of(trace), true);
        return new IndexingRunResumption(resume, context, root).run(plan, mode, changedFiles);
    }

    private ResumedAttempt run(List<IndexingExecutionTarget> plan, IndexingMode mode, List<String> changedFiles) {
        for (ReusedTarget reused : resume.reused()) {
            IndexerExecution execution = reused.execution();
            context.recordTarget(reused.target(), new IndexingArtifact(execution.language(), execution.indexerId(),
                    execution.finalArtifact(), reused.target().projectRelativeRoot()), execution);
        }
        // ADR 0039 §4: interrupted in PROMOTION with every artifact still valid AND the reused targets
        // being exactly those the staged snapshot was built from (R4), the known staged snapshot is
        // promoted directly, without relaunching providers or staging. Otherwise -- interrupted in
        // STAGING (no staged id yet), or the plan no longer matches the snapshot -- everything valid is
        // reused and the snapshot is prepared again: it is never promoted as it stands.
        boolean stagedSnapshotKnown = resume.stagedSnapshotKnown();
        boolean promoteOnly = stagedSnapshotKnown && resume.stagedSnapshotCoversThePlan();
        try {
            context.holdRunDirectory();
            context.publishInProgress(mode, "indexing run resumed: attempt=" + resume.attempt()
                    + ", mode=" + mode + ", " + resume.reused().size() + "/" + plan.size() + " targets reused"
                    + (promoteOnly ? ", promoting the staged snapshot directly" : "")
                    + (stagedSnapshotKnown && !promoteOnly
                            ? ", staged snapshot discarded: its targets differ from the current plan" : ""));
            targets.executeProviders(resume.remaining(), mode, changedFiles);
            reverifyReusedArtifacts(resume.reused());
            if (promoteOnly) {
                resumeStagedPromotion(resume.run().stagedSnapshotId().orElseThrow(), mode);
            } else {
                publication.stage(mode);
                publication.promote();
            }
            return ResumedAttempt.completed(outcomes.persistSuccess(mode, context.ports.clock().instant()));
        } catch (ResumeAborted aborted) {
            IndexingRun terminal = outcomes.persistTerminalFailure(aborted, context.ports.clock().instant());
            // R6: an interruption that surfaces as an aborted resume is still an interruption. The run is
            // left offered for resume; it never triggers the fallback to a new full run.
            return IndexingRunOutcomes.isInterruption(aborted) ? ResumedAttempt.completed(terminal)
                    : ResumedAttempt.aborted(aborted.getMessage());
        } catch (Exception failure) {
            return ResumedAttempt.completed(outcomes.persistTerminalFailure(failure, context.ports.clock().instant()));
        }
    }

    /**
     * TOCTOU (R1-5): the planner verified each reused artifact; this re-reads size and SHA-256 in
     * the same call and thread, immediately before the stager is invoked. The residual window is
     * the stager's own read: the SCIP importer copies the artifact into a frozen file and
     * re-checks its size, so only a size-preserving replacement during that copy could escape, and
     * it would still have to defeat the digest recorded here a moment earlier.
     */
    private void reverifyReusedArtifacts(List<ReusedTarget> reused) throws ResumeAborted {
        if (reused.isEmpty()) return;
        Path runDirectory = existingRunDirectory();
        for (ReusedTarget target : reused) {
            ExecutionCheckpoint checkpoint = target.execution().checkpoint().orElseThrow();
            String scope = portable(target.target().projectRelativeRoot());
            Path artifact = target.execution().finalArtifact();
            // V16: containment is re-decided, physically, before a single byte is re-read (Q5: one decision).
            try {
                ArtifactConfinement.requireInside(runDirectory, artifact);
            } catch (ArtifactConfinement.Escape escape) {
                throw new ResumeAborted("resume aborted: " + escape.getMessage() + " (scope " + scope + ")", escape);
            }
            ExecutionCheckpoints.ArtifactDigest digest;
            try {
                digest = ExecutionCheckpoints.artifactDigest(artifact);
            } catch (ExecutionCheckpoints.Unavailable unavailable) {
                throw new ResumeAborted(
                        "resume aborted: " + unavailable.getMessage() + " (scope " + scope + ")", unavailable);
            }
            if (digest.bytes() != checkpoint.artifactBytes() || !digest.sha256().equals(checkpoint.artifactSha256())) {
                throw new ResumeAborted("resume aborted: artifact changed before staging (scope " + scope + ")");
            }
        }
    }

    /** The run directory as the port names it ({@link ArtifactConfinement} resolves it itself). */
    private Path existingRunDirectory() throws ResumeAborted {
        Path runDirectory = context.ports.markers().runDirectory(context.runId)
                .orElseThrow(() -> new ResumeAborted("resume aborted: run directory is unknown to this runtime"));
        if (!Files.isDirectory(runDirectory)) {
            throw new ResumeAborted("resume aborted: run directory disappeared before staging");
        }
        return runDirectory;
    }

    /**
     * Promotes the snapshot staged by the interrupted attempt. Anything but a commit-uncertain
     * outcome (handled by the regular promotion path) aborts the resume: the staged snapshot may
     * have been reclaimed, and a full run is the safe fallback.
     */
    private void resumeStagedPromotion(String stagedId, IndexingMode mode) throws Exception {
        context.staged = Optional.of(stagedId);
        context.phase = Phase.PROMOTION;
        context.ports.stateStore().saveRun(
                context.running("promoting staged snapshot of the interrupted attempt: mode=" + mode));
        try {
            publication.promote();
        } catch (CommitUncertainException uncertain) {
            throw uncertain;
        } catch (Exception failure) {
            if (IndexingRunOutcomes.isInterruption(failure)) throw failure;
            throw new ResumeAborted("resume aborted: staged snapshot could not be promoted ("
                    + failure.getClass().getSimpleName() + ")", failure);
        }
    }

    /** Outcome of a resumed attempt: a terminal run, or the public reason the resume was aborted. */
    record ResumedAttempt(Optional<IndexingRun> run, Optional<String> abortReason) {
        private static ResumedAttempt completed(IndexingRun run) {
            return new ResumedAttempt(Optional.of(run), Optional.empty());
        }

        private static ResumedAttempt aborted(String reason) {
            return new ResumedAttempt(Optional.empty(), Optional.of(reason));
        }
    }

    private static final class ResumeAborted extends Exception {
        private static final long serialVersionUID = 1L;

        private ResumeAborted(String message) {
            super(message);
        }

        private ResumeAborted(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
