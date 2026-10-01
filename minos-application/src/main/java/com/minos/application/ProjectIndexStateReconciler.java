package com.minos.application;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles persisted project metadata against the active snapshot, which is the authoritative
 * commit record once snapshot publication has completed.
 *
 * <p>Consistent reads remain lock-free. When a repair is required, {@link #reconcile} acquires the
 * same project lifecycle lease as indexing and re-observes every input before mutating metadata.
 * A repair therefore cannot race a cross-process snapshot promotion.</p>
 *
 * <p>{@link #observeStatus} is the read used by status queries: it never takes the lease and never
 * writes. It guarantees the last state its owner published, re-read until the active snapshot is stable
 * (published states are replaced atomically, so a read is never torn), possibly behind the snapshot.
 * When the state lags the snapshot it answers what the repair would publish, computed in memory,
 * except while a run is in flight: an {@code INDEXING} or {@code REFRESHING} state belongs to the
 * run that holds the lease, and a reader that cannot take the lease cannot tell it from an abandoned
 * one, so it reports that state unchanged and leaves the recovery to the next run. {@link #observe}
 * is the same read for a dry-run plan, which always computes the repair so that the plan equals the
 * one a real run would make.</p>
 */
public final class ProjectIndexStateReconciler {
    private static final int MAX_RECONCILIATION_ATTEMPTS = 8;
    private static final String PROJECT_ID = "projectId";

    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;

    public ProjectIndexStateReconciler(CodeKnowledgeSnapshotStore snapshotStore, IndexStateStore stateStore) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public Reconciliation reconcile(UUID projectId) throws IOException {
        return reconcile(Objects.requireNonNull(projectId, PROJECT_ID), false, Mode.PERSIST);
    }

    /**
     * What {@link #reconcile} would answer, without writing anything and without taking the lifecycle
     * lease: when a repair would be needed the repaired state is returned (with {@code repaired} set)
     * but never persisted. For read-only callers that plan what the next run would do, such as a dry
     * run: the plan must be the one {@link #reconcile} would lead to, so a state left
     * {@code INDEXING} by a dead run is repaired in memory like any other lagging state.
     */
    public Reconciliation observe(UUID projectId) throws IOException {
        return reconcile(Objects.requireNonNull(projectId, PROJECT_ID), false, Mode.PLAN);
    }

    /**
     * The read used by status queries: like {@link #observe}, but it does not speak for a run it cannot
     * see. An {@code INDEXING} or {@code REFRESHING} state that lags the snapshot is reported unchanged
     * (a reader without the lease cannot tell an in-flight run from an abandoned one; the next run
     * recovers it under the lease), whereas a plan must anticipate that recovery.
     */
    public Reconciliation observeStatus(UUID projectId) throws IOException {
        return reconcile(Objects.requireNonNull(projectId, PROJECT_ID), false, Mode.STATUS);
    }

    /** What a pass may do: {@code PERSIST} repairs durably under the lease, the other two never write. */
    private enum Mode { PERSIST, PLAN, STATUS }

    private Reconciliation reconcile(UUID projectId, boolean leaseHeld, Mode mode) throws IOException {
        boolean repaired = false;

        for (int attempt = 0; attempt < MAX_RECONCILIATION_ATTEMPTS; attempt++) {
            Optional<CodeKnowledgeSnapshot> activeBefore = loadActive(projectId);
            Optional<ProjectIndexState> persisted = loadProjectState(projectId);
            Optional<CodeKnowledgeSnapshot> activeAfter = loadActive(projectId);

            if (!sameSnapshot(activeBefore, activeAfter)) continue;

            if (activeAfter.isEmpty()) {
                requireNoPersistedActiveSnapshot(persisted, projectId);
                return new Reconciliation(activeAfter, persisted, repaired);
            }

            String authoritativeSnapshotId = activeAfter.orElseThrow().snapshotId();
            if (referencesSnapshot(persisted, authoritativeSnapshotId)) {
                return new Reconciliation(activeAfter, persisted, repaired);
            }

            if (mode == Mode.PERSIST && !leaseHeld) return reconcileUnderLease(projectId);

            Optional<Reconciliation> readOnly =
                    readOnlyAnswer(mode, projectId, activeAfter, persisted, authoritativeSnapshotId);
            if (readOnly.isPresent()) return readOnly.get();

            saveProjectState(repairedState(projectId, authoritativeSnapshotId, persisted), projectId);
            repaired = true;

            Optional<Reconciliation> verified = verifiedRepair(projectId, activeAfter, authoritativeSnapshotId);
            if (verified.isPresent()) return verified.get();
        }

        throw new IOException("active snapshot or project metadata changed repeatedly while reconciling project "
                + projectId);
    }

    private static void requireNoPersistedActiveSnapshot(Optional<ProjectIndexState> persisted, UUID projectId)
            throws IOException {
        if (persisted.flatMap(ProjectIndexState::activeSnapshotId).isPresent()) {
            throw new IOException(
                    "project index state references an active snapshot but the snapshot store has none for project "
                            + projectId);
        }
    }

    /**
     * The answer of a pass that never writes, or empty for {@code PERSIST}, which repairs durably. A status
     * read leaves an in-progress state as it is; a plan returns the repair it anticipates.
     */
    private Optional<Reconciliation> readOnlyAnswer(
            Mode mode,
            UUID projectId,
            Optional<CodeKnowledgeSnapshot> active,
            Optional<ProjectIndexState> persisted,
            String authoritativeSnapshotId) throws IOException {
        if (mode == Mode.PERSIST) return Optional.empty();
        if (mode == Mode.STATUS && inProgress(persisted)) {
            return Optional.of(new Reconciliation(active, persisted, false));
        }
        ProjectIndexState repair = repairedState(projectId, authoritativeSnapshotId, persisted);
        return Optional.of(new Reconciliation(active, Optional.of(repair), true));
    }

    /** The repair, once re-read and still matching the authoritative snapshot; empty when either moved meanwhile. */
    private Optional<Reconciliation> verifiedRepair(
            UUID projectId, Optional<CodeKnowledgeSnapshot> expectedActive, String authoritativeSnapshotId)
            throws IOException {
        Optional<ProjectIndexState> verifiedState = loadProjectState(projectId);
        Optional<CodeKnowledgeSnapshot> verifiedActive = loadActive(projectId);
        if (sameSnapshot(expectedActive, verifiedActive) && referencesSnapshot(verifiedState, authoritativeSnapshotId)) {
            return Optional.of(new Reconciliation(verifiedActive, verifiedState, true));
        }
        return Optional.empty();
    }

    private Reconciliation reconcileUnderLease(UUID projectId) throws IOException {
        try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(projectId)) {
            return reconcile(projectId, true, Mode.PERSIST);
        } catch (RuntimeException failure) {
            throw new IOException("failed to acquire project lifecycle lease for metadata reconciliation: "
                    + projectId, failure);
        }
    }

    private ProjectIndexState repairedState(
            UUID projectId, String authoritativeSnapshotId, Optional<ProjectIndexState> persisted) throws IOException {
        Optional<IndexingRun> matchingRun = loadRuns(projectId).stream()
                .filter(run -> run.status() == IndexingRun.Status.SUCCEEDED)
                .filter(run -> run.activeSnapshotAfter().filter(authoritativeSnapshotId::equals).isPresent())
                .max(Comparator.comparing(run -> run.completedAt().orElse(run.createdAt())));
        Instant updatedAt = matchingRun.flatMap(IndexingRun::completedAt)
                .or(() -> persisted.map(ProjectIndexState::updatedAt))
                .orElseGet(Instant::now);
        Optional<UUID> latestRunId = matchingRun.map(IndexingRun::id)
                .or(() -> persisted.flatMap(ProjectIndexState::latestRunId));
        return new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(authoritativeSnapshotId),
                latestRunId,
                updatedAt,
                Optional.of("reconciled from authoritative active snapshot after incomplete metadata commit"));
    }

    private Optional<CodeKnowledgeSnapshot> loadActive(UUID projectId) throws IOException {
        try {
            return snapshotStore.loadActiveKnowledge(projectId);
        } catch (RuntimeException failure) {
            throw new IOException("failed to read active snapshot for project " + projectId, failure);
        }
    }

    private Optional<ProjectIndexState> loadProjectState(UUID projectId) throws IOException {
        try {
            return stateStore.findProjectState(projectId);
        } catch (RuntimeException failure) {
            throw new IOException("failed to read project index state for project " + projectId, failure);
        }
    }

    private List<IndexingRun> loadRuns(UUID projectId) throws IOException {
        try {
            return stateStore.listRuns(projectId);
        } catch (RuntimeException failure) {
            throw new IOException("failed to read indexing runs for project " + projectId, failure);
        }
    }

    private void saveProjectState(ProjectIndexState state, UUID projectId) throws IOException {
        try {
            stateStore.saveProjectState(state);
        } catch (RuntimeException failure) {
            throw new IOException("active snapshot metadata reconciliation failed for project " + projectId, failure);
        }
    }

    private static boolean sameSnapshot(Optional<CodeKnowledgeSnapshot> first, Optional<CodeKnowledgeSnapshot> second) {
        return first.map(CodeKnowledgeSnapshot::snapshotId).equals(second.map(CodeKnowledgeSnapshot::snapshotId));
    }

    private static boolean inProgress(Optional<ProjectIndexState> state) {
        return state.map(ProjectIndexState::availability)
                .filter(availability -> availability == ProjectIndexState.Availability.INDEXING
                        || availability == ProjectIndexState.Availability.REFRESHING)
                .isPresent();
    }

    private static boolean referencesSnapshot(Optional<ProjectIndexState> state, String snapshotId) {
        return state.flatMap(ProjectIndexState::activeSnapshotId).filter(snapshotId::equals).isPresent();
    }

    public record Reconciliation(
            Optional<CodeKnowledgeSnapshot> activeSnapshot,
            Optional<ProjectIndexState> projectState,
            boolean repaired
    ) {
        public Reconciliation {
            activeSnapshot = Objects.requireNonNull(activeSnapshot, "activeSnapshot");
            projectState = Objects.requireNonNull(projectState, "projectState");
        }
    }
}
