package com.minos.application;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles persisted project metadata against the active snapshot, which is the authoritative
 * commit record once snapshot publication has completed.
 *
 * <p>The service is deliberately fail-closed. A mismatch is either repaired and verified or
 * surfaced as an error; callers must never expose a READY state for a different snapshot.</p>
 */
public final class ProjectIndexStateReconciler {
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;

    public ProjectIndexStateReconciler(CodeKnowledgeSnapshotStore snapshotStore, IndexStateStore stateStore) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public Reconciliation reconcile(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Optional<CodeKnowledgeSnapshot> active = snapshotStore.loadActiveKnowledge(projectId);
        Optional<ProjectIndexState> persisted = stateStore.findProjectState(projectId);

        if (active.isEmpty()) {
            if (persisted.flatMap(ProjectIndexState::activeSnapshotId).isPresent()) {
                throw new IOException("project index state references an active snapshot but the snapshot store has none for project "
                        + projectId);
            }
            return new Reconciliation(active, persisted, false);
        }

        String authoritativeSnapshotId = active.orElseThrow().snapshotId();
        if (persisted.flatMap(ProjectIndexState::activeSnapshotId)
                .filter(authoritativeSnapshotId::equals).isPresent()) {
            return new Reconciliation(active, persisted, false);
        }

        Optional<IndexingRun> matchingRun = stateStore.listRuns(projectId).stream()
                .filter(run -> run.status() == IndexingRun.Status.SUCCEEDED)
                .filter(run -> run.activeSnapshotAfter().filter(authoritativeSnapshotId::equals).isPresent())
                .max(Comparator.comparing(run -> run.completedAt().orElse(run.createdAt())));
        Instant updatedAt = matchingRun.flatMap(IndexingRun::completedAt).orElseGet(Instant::now);
        ProjectIndexState repaired = new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(authoritativeSnapshotId),
                matchingRun.map(IndexingRun::id),
                updatedAt,
                Optional.of("reconciled from authoritative active snapshot after incomplete metadata commit"));
        try {
            stateStore.saveProjectState(repaired);
        } catch (RuntimeException failure) {
            throw new IOException("active snapshot metadata reconciliation failed for project " + projectId, failure);
        }

        Optional<ProjectIndexState> verified = stateStore.findProjectState(projectId);
        if (verified.isEmpty()
                || verified.orElseThrow().activeSnapshotId().filter(authoritativeSnapshotId::equals).isEmpty()
                || verified.orElseThrow().availability() != ProjectIndexState.Availability.READY) {
            throw new IOException("active snapshot metadata reconciliation could not be verified for project " + projectId);
        }
        return new Reconciliation(active, verified, true);
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
