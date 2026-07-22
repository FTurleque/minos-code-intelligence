package com.minos.registry;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Workspace persistant regroupant zéro ou plusieurs projets enregistrés.
 */
public record RegisteredWorkspace(
        UUID id,
        String name,
        List<UUID> projectIds,
        Instant createdAt,
        Instant updatedAt
) {
    public RegisteredWorkspace {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        projectIds = Objects.requireNonNull(projectIds, "projectIds").stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
