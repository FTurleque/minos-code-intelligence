package com.minos.orchestration;

import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stable reconciliation of project metadata against an authoritative snapshot promoter. */
final class AuthoritativeProjectStateReconciler {
    private static final int MAX_ATTEMPTS = 8;

    private AuthoritativeProjectStateReconciler() {
    }

    static ProjectIndexState reconcile(
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

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<String> activeBefore = activeSnapshotId(promoter, projectId);
            ProjectIndexState persisted = stateStore.findProjectState(projectId)
                    .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, observedAt));

            if (activeBefore.isEmpty()) {
                return persisted;
            }

            Optional<String> activeAfter = activeSnapshotId(promoter, projectId);
            if (!activeBefore.equals(activeAfter)) {
                continue;
            }
            if (activeAfter.equals(persisted.activeSnapshotId())) {
                return persisted;
            }

            ProjectIndexState repaired = new ProjectIndexState(
                    projectId,
                    ProjectIndexState.Availability.READY,
                    activeAfter,
                    persisted.latestRunId(),
                    observedAt,
                    Optional.of(detail));
            stateStore.saveProjectState(repaired);

            ProjectIndexState verified = stateStore.findProjectState(projectId)
                    .orElseThrow(() -> new IllegalStateException("reconciled project state was not persisted"));
            Optional<String> verifiedActive = activeSnapshotId(promoter, projectId);
            if (!activeAfter.equals(verifiedActive)) {
                continue;
            }
            if (verifiedActive.equals(verified.activeSnapshotId())) {
                return verified;
            }
        }

        throw new IllegalStateException(
                "authoritative snapshot or project metadata changed repeatedly while reconciling project " + projectId);
    }

    private static Optional<String> activeSnapshotId(SnapshotPromoter promoter, UUID projectId) {
        try {
            return Objects.requireNonNull(promoter.activeSnapshotId(projectId), "activeSnapshotId");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("unable to read authoritative active snapshot for project " + projectId, failure);
        }
    }
}
