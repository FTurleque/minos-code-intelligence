package com.minos.hosted;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Deterministic preview of an explicitly requested retention application. */
public record HostedRetentionPlan(
        UUID tenantId,
        Instant evaluatedAt,
        int auditEventsToRemove,
        List<UUID> archivedWorkspacesToRemove
) {
    public HostedRetentionPlan {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (auditEventsToRemove < 0) throw new IllegalArgumentException("auditEventsToRemove must not be negative");
        archivedWorkspacesToRemove = List.copyOf(Objects.requireNonNull(archivedWorkspacesToRemove, "archivedWorkspacesToRemove"));
        if (archivedWorkspacesToRemove.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("archivedWorkspacesToRemove contains null");
        }
    }
}
