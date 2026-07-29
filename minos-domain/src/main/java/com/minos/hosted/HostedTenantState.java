package com.minos.hosted;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete encrypted-at-rest M27 tenant control-plane state. */
public record HostedTenantState(
        UUID tenantId,
        String name,
        String keyId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        HostedRetentionPolicy retentionPolicy,
        List<HostedPrincipal> members,
        List<SharedWorkspace> workspaces,
        long auditSequence,
        String auditAnchorHash,
        List<HostedAuditEvent> auditEvents
) {
    public static final int MAX_MEMBERS = 1_024;
    public static final int MAX_WORKSPACES = 512;

    public HostedTenantState {
        Objects.requireNonNull(tenantId, "tenantId");
        name = HostedPrincipal.text(name, "tenant name", 256);
        keyId = HostedPrincipal.safeId(keyId, "keyId");
        if (version < 0) throw new IllegalArgumentException("tenant version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        auditEvents = List.copyOf(Objects.requireNonNull(auditEvents, "auditEvents"));
        if (members.isEmpty() || members.size() > MAX_MEMBERS) throw new IllegalArgumentException("invalid member count");
        if (workspaces.size() > MAX_WORKSPACES) throw new IllegalArgumentException("workspace capacity exceeded");
        if (auditEvents.size() > HostedRetentionPolicy.MAX_AUDIT_EVENTS) throw new IllegalArgumentException("audit capacity exceeded");
        if (members.stream().anyMatch(Objects::isNull) || workspaces.stream().anyMatch(Objects::isNull)
                || auditEvents.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("tenant collections contain null");
        HashSet<String> principals = new HashSet<>();
        for (HostedPrincipal member : members) {
            if (!principals.add(member.principalId())) throw new IllegalArgumentException("duplicate principal membership");
        }
        if (members.stream().noneMatch(member -> member.role() == HostedRole.OWNER)) {
            throw new IllegalArgumentException("tenant requires at least one owner");
        }
        HashSet<UUID> workspaceIds = new HashSet<>();
        for (SharedWorkspace workspace : workspaces) {
            if (!tenantId.equals(workspace.tenantId())) throw new IllegalArgumentException("cross-tenant workspace");
            if (!workspaceIds.add(workspace.workspaceId())) throw new IllegalArgumentException("duplicate workspace identity");
        }
        if (auditSequence < 0) throw new IllegalArgumentException("auditSequence must not be negative");
        auditAnchorHash = requireHash(auditAnchorHash);
        String previous = auditAnchorHash;
        long sequence = 0;
        for (HostedAuditEvent event : auditEvents) {
            if (!tenantId.equals(event.tenantId())) throw new IllegalArgumentException("cross-tenant audit event");
            if (!previous.equals(event.previousHash())) throw new IllegalArgumentException("broken audit hash chain");
            if (event.sequence() <= sequence) throw new IllegalArgumentException("audit sequence is not strictly increasing");
            previous = event.hash();
            sequence = event.sequence();
        }
        if (!auditEvents.isEmpty() && auditEvents.getLast().sequence() != auditSequence) {
            throw new IllegalArgumentException("auditSequence does not match last event");
        }
        if (auditEvents.isEmpty() && auditSequence == 0 && !HostedAuditEvent.GENESIS_HASH.equals(auditAnchorHash)) {
            throw new IllegalArgumentException("empty initial audit chain requires genesis anchor");
        }
    }

    private static String requireHash(String value) {
        String normalized = Objects.requireNonNull(value, "auditAnchorHash").trim();
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("auditAnchorHash must be lowercase SHA-256");
        return normalized;
    }
}
