package com.minos.application.team;

import com.minos.hosted.HostedRetentionPlan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read model of the preview of an explicitly requested retention application. */
public record TeamRetentionPlan(UUID tenantId, Instant evaluatedAt, int auditEventsToRemove,
                                List<UUID> archivedWorkspacesToRemove) {
    public TeamRetentionPlan {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        archivedWorkspacesToRemove = List.copyOf(
                Objects.requireNonNull(archivedWorkspacesToRemove, "archivedWorkspacesToRemove"));
    }

    static TeamRetentionPlan of(HostedRetentionPlan value) {
        return new TeamRetentionPlan(value.tenantId(), value.evaluatedAt(), value.auditEventsToRemove(),
                value.archivedWorkspacesToRemove());
    }
}
