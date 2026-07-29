package com.minos.hosted;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-owned collaboration space containing exact static-snapshot bindings. */
public record SharedWorkspace(
        UUID workspaceId,
        UUID tenantId,
        String name,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        List<HostedProjectBinding> bindings
) {
    public static final int MAX_BINDINGS = 128;

    public enum Status { ACTIVE, ARCHIVED }

    public SharedWorkspace {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(tenantId, "tenantId");
        name = HostedPrincipal.text(name, "workspace name", 256);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (status == Status.ACTIVE && archivedAt != null) {
            throw new IllegalArgumentException("active workspace must not have archivedAt");
        }
        if (status == Status.ARCHIVED && archivedAt == null) {
            throw new IllegalArgumentException("archived workspace requires archivedAt");
        }
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (bindings.size() > MAX_BINDINGS) throw new IllegalArgumentException("workspace binding capacity exceeded");
        if (bindings.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("bindings contain null");
        HashSet<UUID> projects = new HashSet<>();
        for (HostedProjectBinding binding : bindings) {
            if (!projects.add(binding.projectId())) throw new IllegalArgumentException("duplicate project binding");
        }
    }
}
