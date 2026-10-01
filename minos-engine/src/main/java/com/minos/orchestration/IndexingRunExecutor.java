package com.minos.orchestration;

import com.minos.io.CommitUncertainException;
import com.minos.orchestration.IndexingResumePlanner.Outcome;
import com.minos.orchestration.IndexingResumePlanner.ReusedTarget;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.ResumeTrace;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Exécute un run d'indexation sous le bail exclusif du projet : providers, mise en snapshot,
 * promotion, métadonnées. Depuis l'ADR 0039 un run {@code INTERRUPTED} offert par le projet est
 * rouvert par défaut (même {@code runId}, tentative suivante) : les cibles dont le point de contrôle
 * est valide sont réutilisées sans relancer leur provider, les autres sont réexécutées.
 */
final class IndexingRunExecutor {
    private static final System.Logger LOGGER = System.getLogger(IndexingRunExecutor.class.getName());

    private IndexingRunExecutor() { }

    static IndexingRun execute(UUID projectId, Path projectRoot, List<IndexingExecutionTarget> targets,
                               IndexingMode mode, List<String> changedFiles,
                               Map<String, IndexerExecutor> executors, SnapshotStager stager,
                               SnapshotPromoter promoter, IndexStateStore stateStore,
                               ResumableRunMarkers markers, ResumableArtifactPolicy artifactPolicy,
                               IndexingResumePolicy resumePolicy, Clock clock) {
        Objects.requireNonNull(resumePolicy, "resumePolicy");
        ValidatedProjectRoot root = validateExecutionRoot(projectRoot, targets, mode);
        Ports ports = new Ports(executors, stager, promoter, stateStore, markers, clock);
        Instant createdAt = clock.instant();
        ProjectIndexState previous = reconcilePreviousState(projectId, ports, createdAt);

        Optional<ResumeTrace> trace = Optional.empty();
        if (resumePolicy != IndexingResumePolicy.NO_RESUME) {
            // The resume plan is requested under the exclusive lease, before any supersession.
            Outcome outcome = IndexingResumePlanner.plan(stateStore, markers, artifactPolicy,
                    new IndexingResumePlanner.Request(previous, root.lexicalRoot(), targets, mode, changedFiles,
                            createdAt, IndexingResumePlanner.DEFAULT_RESUME_TTL));
            Optional<String> refusal;
            switch (outcome) {
                case Outcome.Resume resume -> {
                    ResumedAttempt attempt = executeResumed(resume, root, targets, mode, changedFiles, previous, ports);
                    if (attempt.run().isPresent()) return attempt.run().orElseThrow();
                    refusal = attempt.abortReason();
                    previous = reconcilePreviousState(projectId, ports, createdAt);
                }
                case Outcome.Refused refused -> refusal = Optional.of(refused.reason());
                case Outcome.NotOffered notOffered -> refusal = Optional.empty();
            }
            if (resumePolicy == IndexingResumePolicy.RESUME_ONLY) {
                throw new IllegalStateException("resume-only indexing refused: "
                        + refusal.orElse("no interrupted run to resume"));
            }
            if (!(outcome instanceof Outcome.NotOffered)) {
                trace = Optional.of(new ResumeTrace(1, 0, targets.size(), refusal));
            }
        }

        RunContext context = new RunContext(UUID.randomUUID(), projectId, createdAt, previous, targets, ports, trace, false);
        try {
            // ADR 0039 §2: an INTERRUPTED run never blocks a new run. A run that was not resumed (policy
            // NO_RESUME, or a refused resume) supersedes the offered one so the project offers at most one.
            previous.resumableRunId().ifPresent(resumable -> AuthoritativeProjectStateReconciler.supersede(
                    stateStore, markers, resumable, context.runId, createdAt));
            holdRunDirectory(context);
            publishInProgress(context, mode, "provider execution started: mode=" + mode + ", scopes=" + targets.size());
            executeProviders(context, root, targets, mode, changedFiles);
            stageSnapshot(context, mode);
            promoteSnapshot(context);
            return persistSuccess(context, mode, clock.instant());
        } catch (Exception failure) {
            return persistTerminalFailure(context, failure, clock.instant());
        }
    }

    /**
     * R5: retains the run directory against the retention of every other indexation, this process
     * or another, from before the first provider until the run ends. The marker is the only thing
     * another process can see; without it a concurrent indexation over budget could delete the
     * artifacts this run already produced. A marker that cannot be written only removes the
     * protection, never the run; a resumed run renews the hold its interruption left.
     */
    private static void holdRunDirectory(RunContext context) {
        try {
            context.ports.markers().mark(context.runId);
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS could not hold the run directory of indexing run "
                    + context.runId + " against retention: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Rouvre le run interrompu : ses cibles réutilisées sont injectées sans lancer leur provider, les
     * autres sont exécutées, puis chaque artefact réutilisé est revérifié juste avant la mise en
     * snapshot. Une revérification qui échoue annule la reprise (run FAILED) et l'appelant bascule
     * sur un run complet.
     */
    private static ResumedAttempt executeResumed(
            Outcome.Resume resume,
            ValidatedProjectRoot root,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode,
            List<String> changedFiles,
            ProjectIndexState previous,
            Ports ports
    ) {
        IndexingRun run = resume.run();
        ResumeTrace trace = new ResumeTrace(
                resume.attempt(), resume.reused().size(), resume.remaining().size(), Optional.empty());
        RunContext context = new RunContext(
                run.id(), run.projectId(), run.createdAt(), previous, targets, ports, Optional.of(trace), true);
        for (ReusedTarget reused : resume.reused()) {
            IndexerExecution execution = reused.execution();
            context.record(reused.target(), new IndexingArtifact(execution.language(), execution.indexerId(),
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
            holdRunDirectory(context);
            publishInProgress(context, mode, "indexing run resumed: attempt=" + resume.attempt()
                    + ", mode=" + mode + ", " + resume.reused().size() + "/" + targets.size() + " targets reused"
                    + (promoteOnly ? ", promoting the staged snapshot directly" : "")
                    + (stagedSnapshotKnown && !promoteOnly
                            ? ", staged snapshot discarded: its targets differ from the current plan" : ""));
            executeProviders(context, root, resume.remaining(), mode, changedFiles);
            reverifyReusedArtifacts(context, resume.reused());
            if (promoteOnly) {
                resumeStagedPromotion(context, run.stagedSnapshotId().orElseThrow(), mode);
            } else {
                stageSnapshot(context, mode);
                promoteSnapshot(context);
            }
            return ResumedAttempt.completed(persistSuccess(context, mode, ports.clock().instant()));
        } catch (ResumeAborted aborted) {
            persistTerminalFailure(context, aborted, ports.clock().instant());
            return ResumedAttempt.aborted(aborted.getMessage());
        } catch (Exception failure) {
            return ResumedAttempt.completed(persistTerminalFailure(context, failure, ports.clock().instant()));
        }
    }

    /**
     * TOCTOU (R1-5): the planner verified each reused artifact; this re-reads size and SHA-256 in
     * the same call and thread, immediately before the stager is invoked. The residual window is
     * the stager's own read: the SCIP importer copies the artifact into a frozen file and
     * re-checks its size, so only a size-preserving replacement during that copy could escape, and
     * it would still have to defeat the digest recorded here a moment earlier.
     */
    private static void reverifyReusedArtifacts(RunContext context, List<ReusedTarget> reused) throws ResumeAborted {
        if (reused.isEmpty()) return;
        Path runDirectory = canonicalRunDirectory(context);
        for (ReusedTarget target : reused) {
            ExecutionCheckpoint checkpoint = target.execution().checkpoint().orElseThrow();
            String scope = portable(target.target().projectRelativeRoot());
            Path artifact = target.execution().finalArtifact();
            // V16: containment is re-decided on the canonical path before a single byte is re-read.
            if (Files.isSymbolicLink(artifact)) {
                throw new ResumeAborted("resume aborted: artifact became a symbolic link (scope " + scope + ")");
            }
            try {
                if (!artifact.toRealPath().startsWith(runDirectory)) {
                    throw new ResumeAborted("resume aborted: artifact left the run directory (scope " + scope + ")");
                }
            } catch (IOException failure) {
                throw new ResumeAborted("resume aborted: artifact cannot be resolved before staging (scope " + scope + ")");
            }
            ExecutionCheckpoints.ArtifactDigest digest;
            try {
                digest = ExecutionCheckpoints.artifactDigest(artifact);
            } catch (ExecutionCheckpoints.Unavailable unavailable) {
                throw new ResumeAborted("resume aborted: " + unavailable.getMessage() + " (scope " + scope + ")");
            }
            if (digest.bytes() != checkpoint.artifactBytes() || !digest.sha256().equals(checkpoint.artifactSha256())) {
                throw new ResumeAborted("resume aborted: artifact changed before staging (scope " + scope + ")");
            }
        }
    }

    private static Path canonicalRunDirectory(RunContext context) throws ResumeAborted {
        Path runDirectory = context.ports.markers().runDirectory(context.runId)
                .orElseThrow(() -> new ResumeAborted("resume aborted: run directory is unknown to this runtime"));
        try {
            return runDirectory.toRealPath();
        } catch (IOException failure) {
            throw new ResumeAborted("resume aborted: run directory disappeared before staging");
        }
    }

    /**
     * Promotes the snapshot staged by the interrupted attempt. Anything but a commit-uncertain
     * outcome (handled by the regular promotion path) aborts the resume: the staged snapshot may
     * have been reclaimed, and a full run is the safe fallback.
     */
    private static void resumeStagedPromotion(RunContext context, String stagedId, IndexingMode mode)
            throws Exception {
        context.staged = Optional.of(stagedId);
        context.phase = Phase.PROMOTION;
        context.ports.stateStore().saveRun(running(context, "promoting staged snapshot of the interrupted attempt: mode=" + mode));
        try {
            promoteSnapshot(context);
        } catch (CommitUncertainException uncertain) {
            throw uncertain;
        } catch (Exception failure) {
            throw new ResumeAborted("resume aborted: staged snapshot could not be promoted ("
                    + failure.getClass().getSimpleName() + ")");
        }
    }

    private static ValidatedProjectRoot validateExecutionRoot(
            Path projectRoot,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode
    ) {
        Path lexicalRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(lexicalRoot)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }
        long scopes = targets.stream().map(IndexingExecutionTarget::projectRelativeRoot).distinct().count();
        if (mode == IndexingMode.INCREMENTAL && scopes > 1) {
            throw new IllegalArgumentException(
                    "multi-scope incremental indexing is not qualified; planner must require FULL for this topology");
        }
        try {
            return new ValidatedProjectRoot(lexicalRoot, lexicalRoot.toRealPath());
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "projectRoot could not be resolved to a canonical directory: " + projectRoot, failure);
        }
    }

    private static ProjectIndexState reconcilePreviousState(UUID projectId, Ports ports, Instant createdAt) {
        ProjectIndexState previous = AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease(
                projectId,
                ports.promoter(),
                ports.stateStore(),
                ports.markers(),
                createdAt,
                "reconciled from authoritative active snapshot before new indexing run");
        if (previous.availability() == Availability.INDEXING
                || previous.availability() == Availability.REFRESHING) {
            throw new IllegalStateException("project already has an indexing run in progress: " + projectId);
        }
        return previous;
    }

    private static void publishInProgress(RunContext context, IndexingMode mode, String message) {
        context.ports.stateStore().saveRun(running(context, message));
        context.ports.stateStore().saveProjectState(new ProjectIndexState(
                context.projectId,
                context.previous.activeSnapshotId().isPresent() ? Availability.REFRESHING : Availability.INDEXING,
                context.previous.activeSnapshotId(),
                Optional.of(context.runId),
                context.createdAt,
                Optional.of("indexing run in progress: mode=" + mode)));
    }

    private static void executeProviders(
            RunContext context,
            ValidatedProjectRoot root,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode,
            List<String> changedFiles
    ) throws Exception {
        for (IndexingExecutionTarget target : targets) {
            executeProvider(context, root, target, mode, changedFiles);
        }
    }

    private static void executeProvider(
            RunContext context,
            ValidatedProjectRoot root,
            IndexingExecutionTarget target,
            IndexingMode mode,
            List<String> changedFiles
    ) throws Exception {
        var selection = target.selection();
        String indexerId = selection.indexer().id();
        IndexerExecutor executor = requireExecutor(context.ports.executors(), indexerId);
        Path relative = target.projectRelativeRoot();
        Path executionRoot = requireExecutionRoot(root, relative);
        List<String> scopedChangedFiles = scopedChangedFiles(mode, changedFiles, relative);
        // The scope fingerprint is captured once per scope, before the first provider of that scope
        // starts (V3: N root providers cost one project hash, not N). A source that changes after the
        // capture differs from the recorded fingerprint, so a later resume can never reuse an artifact
        // built from sources it did not fingerprint (fail-closed, ADR 0039 §3). Beyond the source
        // budget (100 000 files / 2 GiB) the capture fails and no checkpoint is persisted.
        CheckpointMaterial material = context.scopeFingerprints.computeIfAbsent(
                relative.normalize(), scope -> captureScopeFingerprint(root.lexicalRoot(), scope));
        IndexingArtifact artifact = Objects.requireNonNull(executor.execute(new IndexingExecutionRequest(
                context.runId,
                context.projectId,
                root.lexicalRoot(),
                executionRoot,
                relative,
                selection,
                mode,
                scopedChangedFiles)), "indexer execution artifact");
        Path artifactPath = validateArtifact(selection, artifact, relative);
        CheckpointOutcome checkpoint = checkpoint(
                material, artifactPath, relative, selection.indexer().version(), mode, scopedChangedFiles,
                context.ports.clock().instant());
        if (checkpoint.withheldReason().isPresent()) context.withheldCheckpoints++;
        context.record(target,
                new IndexingArtifact(artifact.language(), artifact.indexerId(), artifactPath, relative),
                new IndexerExecution(artifact.language(), artifact.indexerId(), artifactPath, checkpoint.checkpoint()));
        context.ports.stateStore().saveRun(running(context,
                "provider artifacts completed: " + context.executions().size()
                        + "/" + context.totalTargets() + ", mode=" + mode + ", scope=" + portable(relative)
                        + checkpoint.withheldReason().map(reason -> ", checkpoint withheld: " + reason).orElse("")));
    }

    private static CheckpointMaterial captureScopeFingerprint(Path projectRoot, Path relative) {
        try {
            return new CheckpointMaterial(Optional.of(ExecutionCheckpoints.scopeFingerprint(projectRoot, relative)),
                    Optional.empty());
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            return new CheckpointMaterial(Optional.empty(), Optional.of(unavailable.getMessage()));
        }
    }

    /**
     * Builds the durable checkpoint of one completed target, or explains why none can be trusted.
     * A withheld checkpoint never changes the outcome of the run: the target simply stays
     * non-resumable.
     */
    private static CheckpointOutcome checkpoint(
            CheckpointMaterial material,
            Path artifactPath,
            Path relative,
            String providerVersion,
            IndexingMode mode,
            List<String> scopedChangedFiles,
            Instant completedAt
    ) {
        if (material.scopeFingerprint().isEmpty()) {
            return CheckpointOutcome.withheld(material.withheldReason().orElse("scope fingerprint unavailable"));
        }
        if (scopedChangedFiles.size() > ExecutionCheckpoint.MAX_CHANGED_FILES) {
            return CheckpointOutcome.withheld("changed-file list exceeds checkpoint budget");
        }
        try {
            ExecutionCheckpoints.ArtifactDigest digest = ExecutionCheckpoints.artifactDigest(artifactPath);
            return CheckpointOutcome.recorded(new ExecutionCheckpoint(
                    relative,
                    providerVersion,
                    digest.bytes(),
                    digest.sha256(),
                    material.scopeFingerprint().orElseThrow(),
                    mode,
                    scopedChangedFiles,
                    completedAt));
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            return CheckpointOutcome.withheld(unavailable.getMessage());
        } catch (IllegalArgumentException invalid) {
            return CheckpointOutcome.withheld("checkpoint material rejected");
        }
    }

    private record CheckpointMaterial(Optional<String> scopeFingerprint, Optional<String> withheldReason) {
        private CheckpointMaterial {
            Objects.requireNonNull(scopeFingerprint, "scopeFingerprint");
            Objects.requireNonNull(withheldReason, "withheldReason");
        }
    }

    private record CheckpointOutcome(Optional<ExecutionCheckpoint> checkpoint, Optional<String> withheldReason) {
        private CheckpointOutcome {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(withheldReason, "withheldReason");
            if (checkpoint.isPresent() == withheldReason.isPresent()) {
                throw new IllegalArgumentException("a checkpoint is either recorded or withheld with a reason");
            }
        }

        private static CheckpointOutcome recorded(ExecutionCheckpoint checkpoint) {
            return new CheckpointOutcome(Optional.of(checkpoint), Optional.empty());
        }

        private static CheckpointOutcome withheld(String reason) {
            return new CheckpointOutcome(Optional.empty(), Optional.of(reason));
        }
    }

    private static IndexerExecutor requireExecutor(Map<String, IndexerExecutor> executors, String indexerId) {
        IndexerExecutor executor = executors.get(indexerId);
        if (executor == null) {
            throw new IllegalStateException("No runtime executor registered for indexer: " + indexerId);
        }
        return executor;
    }

    private static Path requireExecutionRoot(ValidatedProjectRoot root, Path relative) {
        Path executionRoot = root.lexicalRoot().resolve(relative).normalize();
        if (!executionRoot.startsWith(root.lexicalRoot()) || !Files.isDirectory(executionRoot)) {
            throw new IllegalStateException(
                    "provider execution root is missing or outside project: " + portable(relative));
        }
        try {
            Path realExecutionRoot = executionRoot.toRealPath();
            if (!realExecutionRoot.startsWith(root.realRoot())) {
                throw new IllegalStateException(
                        "provider execution root resolves outside project: " + portable(relative));
            }
            // Physical authorization is decided only on canonical paths. The request itself keeps
            // lexical paths so registeredRoot + relativeRoot remains stable even when Windows
            // expands an 8.3 path (or another filesystem alias) during toRealPath().
            return executionRoot;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "provider execution root could not be resolved safely: " + portable(relative), failure);
        }
    }

    private static void stageSnapshot(RunContext context, IndexingMode mode) throws Exception {
        context.phase = Phase.STAGING;
        context.ports.stateStore().saveRun(running(context, "staging project snapshot: mode=" + mode));
        String stagedId = context.ports.stager().stage(new IndexSnapshotStageRequest(
                context.runId, context.projectId, context.artifacts()));
        if (stagedId == null || stagedId.isBlank()) {
            // Etat interne invalide (le port de mise en scene a rendu un identifiant vide), pas un argument :
            // IllegalStateException, dont le nom de classe figure dans le message du run persiste.
            throw new IllegalStateException("stagedSnapshotId must not be blank");
        }
        context.staged = Optional.of(stagedId);
        context.phase = Phase.PROMOTION;
        context.ports.stateStore().saveRun(running(context, "promoting staged snapshot: mode=" + mode));
    }

    private static void promoteSnapshot(RunContext context) throws Exception {
        String stagedId = context.staged.orElseThrow();
        try {
            context.ports.promoter().promote(context.projectId, context.runId, stagedId);
            context.committed = true;
        } catch (CommitUncertainException uncertain) {
            recoverUncertainPromotion(context, stagedId, uncertain);
        }
    }

    private static void recoverUncertainPromotion(
            RunContext context,
            String stagedId,
            CommitUncertainException uncertain
    ) throws CommitUncertainException {
        if (!authoritativeTargetIsActive(context.ports.promoter(), context.projectId, stagedId, uncertain)) throw uncertain;
        context.committed = true;
        context.durabilityAcknowledgementPending = true;
    }

    private static IndexingRun persistSuccess(RunContext context, IndexingMode mode, Instant completedAt) {
        IndexStateStore stateStore = context.ports.stateStore();
        String stagedId = context.staged.orElseThrow();
        String durabilitySuffix = context.durabilityAcknowledgementPending
                ? "; authoritative snapshot confirmed after lost durability acknowledgement"
                : "";
        String checkpointSuffix = context.withheldCheckpoints == 0
                ? ""
                : "; checkpoint withheld for " + context.withheldCheckpoints + "/" + context.totalTargets()
                        + " target(s), not resumable";
        String successMessage = "indexing run completed and snapshot promoted: mode=" + mode
                + ", scopes=" + context.totalTargets() + durabilitySuffix + checkpointSuffix + resumeSuffix(context);
        IndexingRun succeeded = new IndexingRun(
                context.runId,
                context.projectId,
                Status.SUCCEEDED,
                Phase.COMPLETED,
                context.createdAt,
                Optional.of(completedAt),
                context.executions(),
                context.staged,
                context.previous.activeSnapshotId(),
                Optional.of(stagedId),
                Optional.of(successMessage),
                IndexingRun.CURRENT_FORMAT_VERSION,
                context.trace);
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
        AuthoritativeProjectStateReconciler.unmarkQuietly(context.ports.markers(), context.runId);
        return succeeded;
    }

    private static String resumeSuffix(RunContext context) {
        return context.trace
                .filter(trace -> context.resumed)
                .map(trace -> "; resumed attempt=" + trace.attempt() + ": " + trace.reusedTargets()
                        + "/" + context.totalTargets() + " targets reused, " + trace.reexecutedTargets() + " re-executed")
                .orElse("");
    }

    /**
     * Terminal outcome of a run that did not complete. A thread interruption (R1-11) restores the
     * interrupt flag and, when the run already owns a checkpoint or a staged snapshot, leaves an
     * INTERRUPTED run offered for resume instead of a FAILED one.
     */
    private static IndexingRun persistTerminalFailure(RunContext context, Exception failure, Instant completedAt) {
        if (isInterruption(failure)) {
            Thread.currentThread().interrupt();
            IndexingRun interrupted = interruptedRun(context, completedAt);
            if (!context.committed && interrupted.offersResume()) {
                return persistInterruption(context, interrupted, failure, completedAt);
            }
        }
        return persistFailure(context, failure, completedAt);
    }

    private static boolean isInterruption(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** The run as it would be left by an interruption now; whether it is offered for resume is its own rule. */
    private static IndexingRun interruptedRun(RunContext context, Instant completedAt) {
        return new IndexingRun(
                context.runId,
                context.projectId,
                Status.INTERRUPTED,
                context.phase,
                context.createdAt,
                Optional.of(completedAt),
                context.executions(),
                context.staged,
                context.previous.activeSnapshotId(),
                context.previous.activeSnapshotId(),
                Optional.of("indexing run interrupted by thread interruption; resumable targets="
                        + context.checkpointCount() + "/" + context.totalTargets()
                        + context.staged.map(id -> ", staged snapshot retained").orElse("")),
                IndexingRun.CURRENT_FORMAT_VERSION,
                context.trace);
    }

    private static IndexingRun persistInterruption(
            RunContext context, IndexingRun interrupted, Exception failure, Instant completedAt) {
        IndexStateStore stateStore = context.ports.stateStore();
        try {
            context.ports.markers().mark(context.runId);
        } catch (IOException | RuntimeException markerFailure) {
            AuthoritativeProjectStateReconciler.unmarkQuietly(context.ports.markers(), context.runId);
            failure.addSuppressed(markerFailure);
            return persistFailure(context, failure, completedAt);
        }
        persist(() -> stateStore.saveRun(interrupted), failure);
        Availability availability = context.previous.activeSnapshotId().isPresent()
                ? Availability.STALE
                : Availability.FAILED;
        persist(() -> stateStore.saveProjectState(new ProjectIndexState(
                context.projectId,
                availability,
                context.previous.activeSnapshotId(),
                Optional.of(context.runId),
                completedAt,
                Optional.of("indexing run interrupted; resumable run offered"),
                Optional.of(context.runId))), failure);
        return interrupted;
    }

    private static IndexingRun persistFailure(RunContext context, Exception failure, Instant completedAt) {
        String message = failureMessage(failure);
        Optional<String> activeAfter = context.committed ? context.staged : context.previous.activeSnapshotId();
        IndexingRun failed = failedRun(context, completedAt, activeAfter, message);
        if (context.committed) {
            persistCommittedFailure(context, failed, activeAfter, message, completedAt, failure);
        } else {
            persistUncommittedFailure(context, failed, message, completedAt, failure);
        }
        AuthoritativeProjectStateReconciler.unmarkQuietly(context.ports.markers(), context.runId);
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
                context.executions(),
                context.staged,
                context.previous.activeSnapshotId(),
                activeAfter,
                Optional.of(context.committed ? committedPrefix + message : message),
                IndexingRun.CURRENT_FORMAT_VERSION,
                context.trace);
    }

    private static void persistCommittedFailure(
            RunContext context,
            IndexingRun failed,
            Optional<String> activeAfter,
            String message,
            Instant completedAt,
            Exception original
    ) {
        IndexStateStore stateStore = context.ports.stateStore();
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
            Instant completedAt,
            Exception original
    ) {
        IndexStateStore stateStore = context.ports.stateStore();
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

    /** Changed files of an incremental run, relative to the scope, as the provider receives them. */
    static List<String> scopedChangedFiles(IndexingMode mode, List<String> changedFiles, Path relative) {
        if (mode != IndexingMode.INCREMENTAL || relative.toString().isEmpty()) return changedFiles;
        String prefix = portable(relative) + "/";
        return changedFiles.stream().filter(path -> path.startsWith(prefix))
                .map(path -> path.substring(prefix.length())).sorted().toList();
    }

    /** Bounded tolerance for a freshly-written artifact briefly appearing unreadable to this
     * process -- e.g. a real-time antivirus scan holding a transient handle on it -- immediately
     * after the provider process that wrote it has already exited. This is MINOS' own run
     * directory, not attacker-controlled input, so a short poll here carries none of the
     * containment implications a provider-controlled path would. */
    private static final int ARTIFACT_READABILITY_RETRY_ATTEMPTS = 10;
    private static final long ARTIFACT_READABILITY_RETRY_DELAY_MILLIS = 100L;

    private static Path validateArtifact(IndexerNegotiationResult.IndexerSelection selection,
                                         IndexingArtifact artifact, Path expectedRoot) {
        if (artifact.language() != selection.language()) throw new IllegalStateException("executor returned an artifact for an unexpected language");
        if (!artifact.indexerId().equals(selection.indexer().id())) throw new IllegalStateException("executor returned an artifact for an unexpected indexer");
        if (!artifact.projectRelativeRoot().normalize().equals(expectedRoot.normalize())) throw new IllegalStateException("executor returned an artifact for an unexpected project scope");
        Path path = artifact.finalArtifact().toAbsolutePath().normalize();
        if (!awaitReadable(path)) throw new IllegalStateException("final index artifact is missing or unreadable: " + path);
        return path;
    }

    static boolean awaitReadable(Path path) {
        for (int attempt = 1; attempt <= ARTIFACT_READABILITY_RETRY_ATTEMPTS; attempt++) {
            if (Files.exists(path) && Files.isReadable(path)) return true;
            if (attempt == ARTIFACT_READABILITY_RETRY_ATTEMPTS) return false;
            try {
                Thread.sleep(ARTIFACT_READABILITY_RETRY_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static IndexingRun running(RunContext context, String message) {
        Optional<String> before = context.previous.activeSnapshotId();
        return new IndexingRun(context.runId, context.projectId, Status.RUNNING, context.phase, context.createdAt,
                Optional.empty(), context.executions(), context.staged, before, before, Optional.of(message),
                IndexingRun.CURRENT_FORMAT_VERSION, context.trace);
    }

    private static void persist(Runnable action, Exception original) {
        try { action.run(); } catch (RuntimeException failure) { original.addSuppressed(failure); }
    }

    private static String portable(Path path) { return path == null ? "" : path.normalize().toString().replace('\\', '/'); }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record ValidatedProjectRoot(Path lexicalRoot, Path realRoot) {
        private ValidatedProjectRoot {
            Objects.requireNonNull(lexicalRoot, "lexicalRoot");
            Objects.requireNonNull(realRoot, "realRoot");
        }
    }

    private record Ports(
            Map<String, IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            ResumableRunMarkers markers,
            Clock clock
    ) {
        private Ports {
            Objects.requireNonNull(executors, "executors");
            Objects.requireNonNull(stager, "stager");
            Objects.requireNonNull(promoter, "promoter");
            Objects.requireNonNull(stateStore, "stateStore");
            Objects.requireNonNull(markers, "markers");
            Objects.requireNonNull(clock, "clock");
        }
    }

    /** Outcome of a resumed attempt: a terminal run, or the public reason the resume was aborted. */
    private record ResumedAttempt(Optional<IndexingRun> run, Optional<String> abortReason) {
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
    }

    /** Mutable state of one run; artifacts and executions are always listed in plan order. */
    private static final class RunContext {
        private final UUID runId;
        private final UUID projectId;
        private final Instant createdAt;
        private final ProjectIndexState previous;
        private final List<IndexingExecutionTarget> plan;
        private final Ports ports;
        private final Optional<ResumeTrace> trace;
        private final boolean resumed;
        private final Map<IndexingExecutionTarget, IndexingArtifact> artifactsByTarget = new LinkedHashMap<>();
        private final Map<IndexingExecutionTarget, IndexerExecution> executionsByTarget = new LinkedHashMap<>();
        private final Map<Path, CheckpointMaterial> scopeFingerprints = new HashMap<>();
        private Optional<String> staged = Optional.empty();
        private Phase phase = Phase.PROVIDER_EXECUTION;
        private boolean committed;
        private boolean durabilityAcknowledgementPending;
        private int withheldCheckpoints;

        private RunContext(
                UUID runId,
                UUID projectId,
                Instant createdAt,
                ProjectIndexState previous,
                List<IndexingExecutionTarget> plan,
                Ports ports,
                Optional<ResumeTrace> trace,
                boolean resumed
        ) {
            this.runId = runId;
            this.projectId = projectId;
            this.createdAt = createdAt;
            this.previous = previous;
            this.plan = List.copyOf(plan).stream().distinct().toList();
            this.ports = ports;
            this.trace = trace;
            this.resumed = resumed;
        }

        private void record(IndexingExecutionTarget target, IndexingArtifact artifact, IndexerExecution execution) {
            artifactsByTarget.put(target, artifact);
            executionsByTarget.put(target, execution);
        }

        private int totalTargets() {
            return plan.size();
        }

        private List<IndexingArtifact> artifacts() {
            List<IndexingArtifact> artifacts = new ArrayList<>();
            for (IndexingExecutionTarget target : plan) {
                IndexingArtifact artifact = artifactsByTarget.get(target);
                if (artifact != null) artifacts.add(artifact);
            }
            return List.copyOf(artifacts);
        }

        private List<IndexerExecution> executions() {
            List<IndexerExecution> executions = new ArrayList<>();
            for (IndexingExecutionTarget target : plan) {
                IndexerExecution execution = executionsByTarget.get(target);
                if (execution != null) executions.add(execution);
            }
            return List.copyOf(executions);
        }

        private int checkpointCount() {
            return (int) executionsByTarget.values().stream().filter(execution -> execution.checkpoint().isPresent()).count();
        }
    }
}
