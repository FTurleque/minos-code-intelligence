package com.minos.orchestration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * État observable de l'index actif d'un projet.
 *
 * <p>Un rafraîchissement peut échouer sans rendre l'ancien snapshot indisponible :
 * l'état devient alors {@link Availability#STALE} et l'identifiant du snapshot
 * actif précédent est conservé.</p>
 */
public record ProjectIndexState(
        UUID projectId,
        Availability availability,
        Optional<String> activeSnapshotId,
        Optional<UUID> latestRunId,
        Instant updatedAt,
        Optional<String> detail
) {

    public ProjectIndexState {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(availability, "availability");
        activeSnapshotId = Objects.requireNonNull(activeSnapshotId, "activeSnapshotId")
                .map(ProjectIndexState::requireText);
        latestRunId = Objects.requireNonNull(latestRunId, "latestRunId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        detail = Objects.requireNonNull(detail, "detail")
                .map(ProjectIndexState::requireText);

        if ((availability == Availability.READY || availability == Availability.STALE)
                && activeSnapshotId.isEmpty()) {
            throw new IllegalArgumentException(availability + " requires an active snapshot");
        }
    }

    public static ProjectIndexState neverIndexed(UUID projectId, Instant observedAt) {
        return new ProjectIndexState(
                projectId,
                Availability.NEVER_INDEXED,
                Optional.empty(),
                Optional.empty(),
                observedAt,
                Optional.empty()
        );
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("text value must not be blank");
        }
        return value;
    }

    public enum Availability {
        NEVER_INDEXED,
        INDEXING,
        REFRESHING,
        READY,
        STALE,
        FAILED
    }
}
