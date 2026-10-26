package com.minos.application.team;

import com.minos.hosted.SharedWorkspace;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read model of a shared workspace. {@code status} is the name of the workspace status ({@code ACTIVE} or
 * {@code ARCHIVED}); {@code archivedAt} is null while the workspace is active.
 */
public record TeamWorkspace(
        UUID workspaceId,
        UUID tenantId,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        List<TeamBinding> bindings
) {
    public TeamWorkspace {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }

    static TeamWorkspace of(SharedWorkspace value) {
        return new TeamWorkspace(value.workspaceId(), value.tenantId(), value.name(), value.status().name(),
                value.createdAt(), value.updatedAt(), value.archivedAt(),
                value.bindings().stream().map(TeamBinding::of).toList());
    }
}
