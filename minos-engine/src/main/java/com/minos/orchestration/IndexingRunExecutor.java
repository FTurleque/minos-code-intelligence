package com.minos.orchestration;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexingResumePlanner.Outcome;
import com.minos.orchestration.IndexingRun.ResumeTrace;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.IndexingRunResumption.ResumedAttempt;
import com.minos.orchestration.IndexingTargetExecution.ValidatedProjectRoot;
import com.minos.orchestration.ProjectIndexState.Availability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Runs an indexing run under the exclusive lease of the project: providers, snapshot staging,
 * promotion, metadata. Since ADR 0039 an {@code INTERRUPTED} run offered by the project is reopened
 * by default (same {@code runId}, next attempt): the targets whose checkpoint is valid are reused
 * without relaunching their provider, the others are run again.
 *
 * <p>This class keeps the entry point, the order of the refusals and the sequence of the phases; each phase is a
 * class of its own, built for the run: {@link IndexingTargetExecution}, {@link IndexingSnapshotPublication},
 * {@link IndexingRunOutcomes}, and {@link IndexingRunResumption} for a resumed attempt.</p>
 */
final class IndexingRunExecutor {
    /** Every phase of a run logs under the name of this class, the entry point of the run. */
    static final System.Logger LOGGER = System.getLogger(IndexingRunExecutor.class.getName());

    private IndexingRunExecutor() { }

    /**
     * Validates the request (resume policy, then root, then ports in the order of {@link IndexingRunContext.Ports}),
     * then runs the run, resumed or new. Neither record validates at construction: the order and the messages of the
     * refusals are those of this method.
     */
    static IndexingRun execute(RunRequest request, Collaborators collaborators) {
        UUID projectId = request.projectId();
        List<IndexingExecutionTarget> targets = request.targets();
        IndexingMode mode = request.mode();
        List<String> changedFiles = request.changedFiles();
        IndexingResumePolicy resumePolicy = request.resumePolicy();
        IndexStateStore stateStore = collaborators.stateStore();
        ResumableRunMarkers markers = collaborators.markers();
        Clock clock = collaborators.clock();
        Objects.requireNonNull(resumePolicy, "resumePolicy");
        ValidatedProjectRoot root = validateExecutionRoot(request.projectRoot(), targets, mode);
        IndexingRunContext.Ports ports = new IndexingRunContext.Ports(collaborators.executors(),
                collaborators.stager(), collaborators.promoter(), stateStore, markers, request.moduleAssignment(), clock,
                collaborators.fingerprints(), request.rootScopeFingerprint());
        Instant createdAt = clock.instant();
        ProjectIndexState previous = reconcilePreviousState(projectId, ports, createdAt);

        Optional<ResumeTrace> trace = Optional.empty();
        if (resumePolicy != IndexingResumePolicy.NO_RESUME) {
            // The resume plan is requested under the exclusive lease, before any supersession.
            Outcome outcome = IndexingResumePlanner.plan(stateStore, markers, collaborators.artifactPolicy(),
                    new IndexingResumePlanner.Request(previous, root.lexicalRoot(), targets, mode, changedFiles,
                            createdAt, IndexingResumePlanner.DEFAULT_RESUME_TTL, ports.fingerprints()));
            Optional<String> refusal;
            switch (outcome) {
                case Outcome.Resume resume -> {
                    ResumedAttempt attempt = IndexingRunResumption.attempt(
                            resume, root, targets, mode, changedFiles, previous, ports);
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

        IndexingRunContext context = new IndexingRunContext(
                new IndexingRunContext.Identity(UUID.randomUUID(), projectId, createdAt), previous, targets, ports,
                trace, false);
        IndexingTargetExecution execution = new IndexingTargetExecution(context, root);
        IndexingSnapshotPublication publication = new IndexingSnapshotPublication(context, execution);
        IndexingRunOutcomes outcomes = new IndexingRunOutcomes(context);
        try {
            // ADR 0039 §2: an INTERRUPTED run never blocks a new run. A run that was not resumed (policy
            // NO_RESUME, or a refused resume) supersedes the offered one so the project offers at most one.
            previous.resumableRunId().ifPresent(resumable -> AuthoritativeProjectStateReconciler.supersede(
                    stateStore, markers, resumable, context.runId, createdAt));
            context.holdRunDirectory();
            context.publishInProgress(mode, "provider execution started: mode=" + mode + ", scopes=" + targets.size());
            execution.executeProviders(targets, mode, changedFiles);
            publication.stage(mode);
            publication.promote();
            return outcomes.persistSuccess(mode, clock.instant());
        } catch (Exception failure) {
            return outcomes.persistTerminalFailure(failure, clock.instant());
        }
    }

    private static ValidatedProjectRoot validateExecutionRoot(
            Path projectRoot,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode
    ) {
        Path lexicalRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(lexicalRoot)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + leafName(projectRoot));
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
                    "projectRoot could not be resolved to a canonical directory: " + leafName(projectRoot), failure);
        }
    }

    private static ProjectIndexState reconcilePreviousState(
            UUID projectId, IndexingRunContext.Ports ports, Instant createdAt) {
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

    /**
     * Inputs specific to one run. No validation here: {@link #execute(RunRequest, Collaborators)}
     * checks the resume policy, then the root, then {@code moduleAssignment} and {@code rootScopeFingerprint}
     * with the other ports.
     */
    record RunRequest(
            UUID projectId,
            Path projectRoot,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode,
            List<String> changedFiles,
            IndexingResumePolicy resumePolicy,
            ModuleAssignmentRule moduleAssignment,
            Optional<String> rootScopeFingerprint
    ) {
    }

    /**
     * Collaborators of the lifecycle service, built once with it. No validation here: the service checks
     * them at its construction, and {@link #execute(RunRequest, Collaborators)} in the order of
     * {@link IndexingRunContext.Ports}.
     */
    record Collaborators(
            Map<String, IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            ResumableRunMarkers markers,
            ResumableArtifactPolicy artifactPolicy,
            Clock clock,
            ProjectFingerprintService fingerprints
    ) {
    }
}
