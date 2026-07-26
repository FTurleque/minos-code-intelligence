package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace immuable d'un run d'indexation projet.
 */
public record IndexingRun(
        UUID id,
        UUID projectId,
        Status status,
        Phase phase,
        Instant createdAt,
        Optional<Instant> completedAt,
        List<IndexerExecution> executions,
        Optional<String> stagedSnapshotId,
        Optional<String> activeSnapshotBefore,
        Optional<String> activeSnapshotAfter,
        Optional<String> message
) {

    public IndexingRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(createdAt, "createdAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
        stagedSnapshotId = normalizeText(stagedSnapshotId, "stagedSnapshotId");
        activeSnapshotBefore = normalizeText(activeSnapshotBefore, "activeSnapshotBefore");
        activeSnapshotAfter = normalizeText(activeSnapshotAfter, "activeSnapshotAfter");
        message = normalizeText(message, "message");

        if (status == Status.RUNNING && completedAt.isPresent()) {
            throw new IllegalArgumentException("a running run must not have completedAt");
        }
        if (status != Status.RUNNING && completedAt.isEmpty()) {
            throw new IllegalArgumentException("a terminal run requires completedAt");
        }
        if (status == Status.SUCCEEDED && phase != Phase.COMPLETED) {
            throw new IllegalArgumentException("a successful run must be completed");
        }
        if (status == Status.SUCCEEDED && activeSnapshotAfter.isEmpty()) {
            throw new IllegalArgumentException("a successful run requires an active snapshot");
        }
    }

    private static Optional<String> normalizeText(Optional<String> value, String label) {
        Objects.requireNonNull(value, label);
        return value.map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(label + " must not contain blank text");
            }
            return text;
        });
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum Phase {
        PROVIDER_EXECUTION,
        STAGING,
        PROMOTION,
        COMPLETED
    }

    public record IndexerExecution(Language language, String indexerId, Path finalArtifact) {
        public IndexerExecution {
            Objects.requireNonNull(language, "language");
            if (indexerId == null || indexerId.isBlank()) {
                throw new IllegalArgumentException("indexerId must not be blank");
            }
            Objects.requireNonNull(finalArtifact, "finalArtifact");
        }
    }
}
