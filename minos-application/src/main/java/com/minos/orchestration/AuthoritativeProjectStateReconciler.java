package com.minos.orchestration;

import com.minos.orchestration.IndexingRun.Phase;
import com.minos.orchestration.IndexingRun.Status;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stable reconciliation of project metadata against an authoritative snapshot promoter. */
final class AuthoritativeProjectStateReconciler {
    private static final int MAX_ATTEMPTS = 8;
    private static final Comparator<IndexingRun> RUN_ORDER =
            Comparator.comparing(IndexingRun::createdAt).thenComparing(IndexingRun::id);

    private AuthoritativeProjectStateReconciler() {
    }

    static ProjectIndexState reconcile(
            UUID projectId,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail
    ) {
        return reconcile(projectId, promoter, stateStore, observedAt, detail, false);
    }

    /**
     * Reconciles authoritative state while the caller owns the exclusive project lifecycle lease.
     * Any pre-existing RUNNING run is therefore abandoned by definition: a live owner could not
     * coexist with this lease. Runs whose staged snapshot is already authoritative are recovered as
     * successful commits; every other abandoned run is finalized as failed before project metadata
     * is made terminal again.
     */
    static ProjectIndexState reconcileUnderExclusiveLease(
            UUID projectId,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail
    ) {
        return reconcile(projectId, promoter, stateStore, observedAt, detail, true);
    }

    private static ProjectIndexState reconcile(
            UUID projectId,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail,
            boolean exclusiveLeaseHeld
    ) {
        validateArguments(projectId, promoter, stateStore, observedAt, detail);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Decision decision = reconcileAttempt(
                    projectId, promoter, stateStore, observedAt, detail, exclusiveLeaseHeld);
            if (decision.resolved()) return decision.state().orElseThrow();
        }
        throw new IllegalStateException(
                "authoritative snapshot or project metadata changed repeatedly while reconciling project " + projectId);
    }

    private static Decision reconcileAttempt(
            UUID projectId,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail,
            boolean exclusiveLeaseHeld
    ) {
        ActiveSnapshotObservation activeBefore = observe(promoter, projectId);
        ProjectIndexState persisted = persistedState(projectId, stateStore, observedAt);
        if (activeBefore.status() == ActiveSnapshotObservation.Status.UNSUPPORTED) {
            return Decision.resolved(persisted);
        }

        ActiveSnapshotObservation activeAfter = observe(promoter, projectId);
        if (!activeBefore.equals(activeAfter)) return Decision.retry();

        Optional<Decision> recovery = recoverIfRequired(
                projectId,
                activeAfter,
                promoter,
                stateStore,
                observedAt,
                detail,
                exclusiveLeaseHeld);
        return recovery.orElseGet(() -> reconcileStableSnapshot(
                projectId, activeAfter, persisted, promoter, stateStore, observedAt, detail));
    }

    private static Optional<Decision> recoverIfRequired(
            UUID projectId,
            ActiveSnapshotObservation active,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail,
            boolean exclusiveLeaseHeld
    ) {
        if (!exclusiveLeaseHeld) return Optional.empty();
        Recovery recovery = recoverAbandonedRuns(projectId, active, stateStore, observedAt);
        ProjectIndexState current = persistedState(projectId, stateStore, observedAt);
        if (recovery.recoveredCount() == 0 && !inProgress(current)) return Optional.empty();

        ProjectIndexState recovered = recoveredProjectState(
                projectId, active, current, recovery, stateStore, observedAt, detail);
        stateStore.saveProjectState(recovered);
        ProjectIndexState verified = stateStore.findProjectState(projectId)
                .orElseThrow(() -> new IllegalStateException("recovered project state was not persisted"));
        ActiveSnapshotObservation verifiedActive = observe(promoter, projectId);
        if (!active.equals(verifiedActive)) return Optional.of(Decision.retry());

        verifyRecoveredState(projectId, verified, verifiedActive, stateStore);
        return Optional.of(Decision.resolved(verified));
    }

    private static Decision reconcileStableSnapshot(
            UUID projectId,
            ActiveSnapshotObservation active,
            ProjectIndexState persisted,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail
    ) {
        if (active.status() == ActiveSnapshotObservation.Status.NO_ACTIVE_SNAPSHOT) {
            return noActiveSnapshotDecision(projectId, persisted);
        }

        String authoritativeId = active.snapshotId().orElseThrow();
        if (persisted.activeSnapshotId().equals(Optional.of(authoritativeId))) {
            return Decision.resolved(persisted);
        }

        ProjectIndexState repaired = new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(authoritativeId),
                persisted.latestRunId(),
                observedAt,
                Optional.of(detail));
        stateStore.saveProjectState(repaired);
        return verifyRepair(projectId, active, promoter, stateStore);
    }

    private static Decision noActiveSnapshotDecision(UUID projectId, ProjectIndexState persisted) {
        if (persisted.activeSnapshotId().isPresent()) {
            throw missingAuthoritativeSnapshot(projectId, persisted);
        }
        return Decision.resolved(persisted);
    }

    private static Decision verifyRepair(
            UUID projectId,
            ActiveSnapshotObservation expectedActive,
            SnapshotPromoter promoter,
            IndexStateStore stateStore
    ) {
        ProjectIndexState verified = stateStore.findProjectState(projectId)
                .orElseThrow(() -> new IllegalStateException("reconciled project state was not persisted"));
        ActiveSnapshotObservation verifiedActive = observe(promoter, projectId);
        if (!expectedActive.equals(verifiedActive)) return Decision.retry();
        return verified.activeSnapshotId().equals(verifiedActive.snapshotId())
                ? Decision.resolved(verified)
                : Decision.retry();
    }

    private static void verifyRecoveredState(
            UUID projectId,
            ProjectIndexState verified,
            ActiveSnapshotObservation verifiedActive,
            IndexStateStore stateStore
    ) {
        boolean runningRemains = stateStore.listRuns(projectId).stream()
                .anyMatch(run -> run.status() == Status.RUNNING);
        if (runningRemains) {
            throw new IllegalStateException(
                    "RUNNING indexing run remained after exclusive lifecycle recovery for project " + projectId);
        }
        if (!verified.activeSnapshotId().equals(verifiedActive.snapshotId()) || inProgress(verified)) {
            throw new IllegalStateException(
                    "project metadata remained inconsistent after exclusive lifecycle recovery for project " + projectId);
        }
    }

    private static Recovery recoverAbandonedRuns(
            UUID projectId,
            ActiveSnapshotObservation active,
            IndexStateStore stateStore,
            Instant observedAt
    ) {
        List<IndexingRun> running = stateStore.listRuns(projectId).stream()
                .filter(run -> run.status() == Status.RUNNING)
                .sorted(RUN_ORDER)
                .toList();
        for (IndexingRun run : running) {
            boolean committed = active.status() == ActiveSnapshotObservation.Status.ACTIVE
                    && run.phase() == Phase.PROMOTION
                    && run.stagedSnapshotId().equals(active.snapshotId());
            stateStore.saveRun(terminalRecovery(run, active.snapshotId(), observedAt, committed));
        }
        Optional<IndexingRun> latest = stateStore.listRuns(projectId).stream().max(RUN_ORDER);
        return new Recovery(running.size(), latest);
    }

    private static IndexingRun terminalRecovery(
            IndexingRun run,
            Optional<String> authoritativeSnapshot,
            Instant observedAt,
            boolean committed
    ) {
        String message = committed
                ? "recovered abandoned indexing run: staged snapshot was already authoritative after lifecycle lease reacquisition"
                : "recovered abandoned indexing run after exclusive lifecycle lease reacquisition";
        return new IndexingRun(
                run.id(),
                run.projectId(),
                committed ? Status.SUCCEEDED : Status.FAILED,
                committed ? Phase.COMPLETED : run.phase(),
                run.createdAt(),
                Optional.of(observedAt),
                run.executions(),
                run.stagedSnapshotId(),
                run.activeSnapshotBefore(),
                authoritativeSnapshot,
                Optional.of(message));
    }

    private static ProjectIndexState recoveredProjectState(
            UUID projectId,
            ActiveSnapshotObservation active,
            ProjectIndexState persisted,
            Recovery recovery,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail
    ) {
        Optional<IndexingRun> latest = recovery.latestRun()
                .or(() -> persisted.latestRunId().flatMap(stateStore::findRun));
        Optional<UUID> latestRunId = latest.map(IndexingRun::id).or(() -> persisted.latestRunId());
        String recoveryDetail = detail + "; recovered abandoned indexing lifecycle"
                + (recovery.recoveredCount() == 0 ? " metadata" : " runs=" + recovery.recoveredCount());

        if (active.status() == ActiveSnapshotObservation.Status.NO_ACTIVE_SNAPSHOT) {
            return new ProjectIndexState(
                    projectId,
                    ProjectIndexState.Availability.FAILED,
                    Optional.empty(),
                    latestRunId,
                    observedAt,
                    Optional.of(recoveryDetail));
        }

        String authoritativeId = active.snapshotId().orElseThrow();
        boolean latestSucceededForAuthority = latest
                .filter(run -> run.status() == Status.SUCCEEDED)
                .flatMap(IndexingRun::activeSnapshotAfter)
                .filter(authoritativeId::equals)
                .isPresent();
        ProjectIndexState.Availability availability = latestSucceededForAuthority
                ? ProjectIndexState.Availability.READY
                : ProjectIndexState.Availability.STALE;
        return new ProjectIndexState(
                projectId,
                availability,
                Optional.of(authoritativeId),
                latestRunId,
                observedAt,
                Optional.of(recoveryDetail));
    }

    private static ProjectIndexState persistedState(
            UUID projectId,
            IndexStateStore stateStore,
            Instant observedAt
    ) {
        return stateStore.findProjectState(projectId)
                .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, observedAt));
    }

    private static void validateArguments(
            UUID projectId,
            SnapshotPromoter promoter,
            IndexStateStore stateStore,
            Instant observedAt,
            String detail
    ) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(promoter, "promoter");
        Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(detail, "detail");
    }

    private static boolean inProgress(ProjectIndexState state) {
        return state.availability() == ProjectIndexState.Availability.INDEXING
                || state.availability() == ProjectIndexState.Availability.REFRESHING;
    }

    private static ActiveSnapshotObservation observe(SnapshotPromoter promoter, UUID projectId) {
        try {
            return Objects.requireNonNull(promoter.observeActiveSnapshot(projectId), "observeActiveSnapshot");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("unable to read authoritative active snapshot for project " + projectId, failure);
        }
    }

    private static IllegalStateException missingAuthoritativeSnapshot(
            UUID projectId,
            ProjectIndexState persisted
    ) {
        return new IllegalStateException(
                "project metadata references snapshot " + persisted.activeSnapshotId().orElseThrow()
                        + " but the authoritative snapshot store has no active snapshot for project " + projectId);
    }

    private record Decision(Optional<ProjectIndexState> state) {
        private Decision {
            state = Objects.requireNonNull(state, "state");
        }

        private static Decision resolved(ProjectIndexState state) {
            return new Decision(Optional.of(Objects.requireNonNull(state, "state")));
        }

        private static Decision retry() {
            return new Decision(Optional.empty());
        }

        private boolean resolved() {
            return state.isPresent();
        }
    }

    private record Recovery(int recoveredCount, Optional<IndexingRun> latestRun) {
        private Recovery {
            if (recoveredCount < 0) throw new IllegalArgumentException("recoveredCount must not be negative");
            latestRun = Objects.requireNonNull(latestRun, "latestRun");
        }
    }
}
