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
 * <p>Consistent reads remain lock-free. When a repair is required, reconciliation acquires the
 * same project lifecycle lease as indexing and re-observes every input before mutating metadata.
 * A repair therefore cannot race a cross-process snapshot promotion.</p>
 */
public final class ProjectIndexStateReconciler {
    private static final int MAX_RECONCILIATION_ATTEMPTS = 8;

    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;

    public ProjectIndexStateReconciler(CodeKnowledgeSnapshotStore snapshotStore, IndexStateStore stateStore) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public Reconciliation reconcile(UUID projectId) throws IOException {
        return reconcile(Objects.requireNonNull(projectId, "projectId"), false);
    }

    private Reconciliation reconcile(UUID projectId, boolean leaseHeld) throws IOException {
        boolean repaired = false;

        for (int attempt = 0; attempt < MAX_RECONCILIATION_ATTEMPTS; attempt++) {
            Optional<CodeKnowledgeSnapshot> activeBefore = loadActive(projectId);
            Optional<ProjectIndexState> persisted = loadProjectState(projectId);
            Optional<CodeKnowledgeSnapshot> activeAfter = loadActive(projectId);

            if (!sameSnapshot(activeBefore, activeAfter)) continue;

            if (activeAfter.isEmpty()) {
                if (persisted.flatMap(ProjectIndexState::activeSnapshotId).isPresent()) {
                    throw new IOException(
                            "project index state references an active snapshot but the snapshot store has none for project "
                                    + projectId);
                }
                return new Reconciliation(activeAfter, persisted, repaired);
            }

            String authoritativeSnapshotId = activeAfter.orElseThrow().snapshotId();
            if (referencesSnapshot(persisted, authoritativeSnapshotId)) {
                return new Reconciliation(activeAfter, persisted, repaired);
            }

            if (!leaseHeld) {
                try (IndexStateStore.ProjectLease ignored = stateStore.acquireProjectLease(projectId)) {
                    return reconcile(projectId, true);
                } catch (RuntimeException failure) {
                    throw new IOException("failed to acquire project lifecycle lease for metadata reconciliation: "
                            + projectId, failure);
                }
            }

            Optional<IndexingRun> matchingRun = loadRuns(projectId).stream()
                    .filter(run -> run.status() == IndexingRun.Status.SUCCEEDED)
                    .filter(run -> run.activeSnapshotAfter().filter(authoritativeSnapshotId::equals).isPresent())
                    .max(Comparator.comparing(run -> run.completedAt().orElse(run.createdAt())));
            Instant updatedAt = matchingRun.flatMap(IndexingRun::completedAt)
                    .or(() -> persisted.map(ProjectIndexState::updatedAt))
                    .orElseGet(Instant::now);
            Optional<UUID> latestRunId = matchingRun.map(IndexingRun::id)
                    .or(() -> persisted.flatMap(ProjectIndexState::latestRunId));
            ProjectIndexState repair = new ProjectIndexState(
                    projectId,
                    ProjectIndexState.Availability.READY,
                    Optional.of(authoritativeSnapshotId),
                    latestRunId,
                    updatedAt,
                    Optional.of("reconciled from authoritative active snapshot after incomplete metadata commit"));
            saveProjectState(repair, projectId);
            repaired = true;

            Optional<ProjectIndexState> verifiedState = loadProjectState(projectId);
            Optional<CodeKnowledgeSnapshot> verifiedActive = loadActive(projectId);
            if (!sameSnapshot(activeAfter, verifiedActive)) continue;
            if (referencesSnapshot(verifiedState, authoritativeSnapshotId)) {
                return new Reconciliation(verifiedActive, verifiedState, true);
            }
        }

        throw new IOException("active snapshot or project metadata changed repeatedly while reconciling project "
                + projectId);
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
