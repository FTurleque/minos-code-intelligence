package com.minos.orchestration;

import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation.Status;
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
            ActiveSnapshotObservation activeBefore = observe(promoter, projectId);
            ProjectIndexState persisted = stateStore.findProjectState(projectId)
                    .orElseGet(() -> ProjectIndexState.neverIndexed(projectId, observedAt));

            if (activeBefore.status() == Status.UNSUPPORTED) {
                return persisted;
            }

            ActiveSnapshotObservation activeAfter = observe(promoter, projectId);
            if (!activeBefore.equals(activeAfter)) {
                continue;
            }

            if (activeAfter.status() == Status.NO_ACTIVE_SNAPSHOT) {
                if (persisted.activeSnapshotId().isPresent()) {
                    throw missingAuthoritativeSnapshot(projectId, persisted);
                }
                return persisted;
            }

            String authoritativeId = activeAfter.snapshotId().orElseThrow();
            if (persisted.activeSnapshotId().equals(Optional.of(authoritativeId))) {
                return persisted;
            }

            ProjectIndexState repaired = new ProjectIndexState(
                    projectId,
                    ProjectIndexState.Availability.READY,
                    Optional.of(authoritativeId),
                    persisted.latestRunId(),
                    observedAt,
                    Optional.of(detail));
            stateStore.saveProjectState(repaired);

            ProjectIndexState verified = stateStore.findProjectState(projectId)
                    .orElseThrow(() -> new IllegalStateException("reconciled project state was not persisted"));
            ActiveSnapshotObservation verifiedActive = observe(promoter, projectId);
            if (!activeAfter.equals(verifiedActive)) {
                continue;
            }
            if (verified.activeSnapshotId().equals(verifiedActive.snapshotId())) {
                return verified;
            }
        }

        throw new IllegalStateException(
                "authoritative snapshot or project metadata changed repeatedly while reconciling project " + projectId);
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
}
