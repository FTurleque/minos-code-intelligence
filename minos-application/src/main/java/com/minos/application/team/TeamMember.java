package com.minos.application.team;

import com.minos.hosted.HostedPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Read model of a tenant member: its role and the names of the permissions the engine grants it, sorted. */
public record TeamMember(String principalId, String displayName, TeamRole role, List<String> permissions,
                         Instant createdAt) {
    public TeamMember {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        Objects.requireNonNull(createdAt, "createdAt");
    }

    static TeamMember of(HostedPrincipal value) {
        return new TeamMember(value.principalId(), value.displayName(), TeamRole.valueOf(value.role().name()),
                value.role().permissions().stream().map(Enum::name).sorted().toList(), value.createdAt());
    }
}
