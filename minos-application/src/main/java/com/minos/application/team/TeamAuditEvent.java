package com.minos.application.team;

import com.minos.hosted.HostedAuditEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read model of one event of the tenant audit chain. {@code outcome} is {@code ALLOWED} or {@code DENIED}. */
public record TeamAuditEvent(
        long sequence,
        UUID tenantId,
        Instant occurredAt,
        String principalId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String requestId,
        String keyId,
        String previousHash,
        String hash
) {
    public TeamAuditEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(hash, "hash");
    }

    static TeamAuditEvent of(HostedAuditEvent value) {
        return new TeamAuditEvent(value.sequence(), value.tenantId(), value.occurredAt(), value.principalId(),
                value.action(), value.resourceType(), value.resourceId(), value.outcome().name(), value.requestId(),
                value.keyId(), value.previousHash(), value.hash());
    }
}
