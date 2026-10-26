package com.minos.application.team;

import com.minos.hosted.HostedTenantState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read model of a tenant: its identity, its version, the size of its collections and its retention policy. */
public record TeamTenant(
        UUID tenantId,
        String name,
        String keyId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        int memberCount,
        int workspaceCount,
        long auditSequence,
        TeamRetentionPolicy retentionPolicy
) {
    public TeamTenant {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    }

    static TeamTenant of(HostedTenantState value) {
        return new TeamTenant(value.tenantId(), value.name(), value.keyId(), value.version(), value.createdAt(),
                value.updatedAt(), value.members().size(), value.workspaces().size(), value.auditSequence(),
                TeamRetentionPolicy.of(value.retentionPolicy()));
    }
}
