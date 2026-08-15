package com.minos.registry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Projet connu du registre local MINOS.
 *
 * <p>L'identifiant est attribué par le registre et persiste indépendamment du
 * chemin utilisé comme localisation courante.</p>
 */
public record RegisteredProject(
        UUID id,
        Path rootPath,
        String displayName,
        Optional<UUID> workspaceId,
        Instant createdAt,
        Instant updatedAt
) {
    public RegisteredProject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rootPath, "rootPath");
        rootPath = rootPath.toAbsolutePath().normalize();
        ProjectRegistryLimits.requireName(displayName, "displayName");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
