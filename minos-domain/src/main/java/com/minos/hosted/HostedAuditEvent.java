package com.minos.hosted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Hash-chained security audit entry. */
public record HostedAuditEvent(
        long sequence,
        UUID tenantId,
        Instant occurredAt,
        String principalId,
        String action,
        String resourceType,
        String resourceId,
        Outcome outcome,
        String requestId,
        String keyId,
        String previousHash,
        String hash
) {
    public static final String GENESIS_HASH = "0".repeat(64);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public enum Outcome { ALLOWED, DENIED }

    public HostedAuditEvent {
        if (sequence < 1) throw new IllegalArgumentException("audit sequence must be positive");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        principalId = HostedPrincipal.safeId(principalId, "principalId");
        action = HostedPrincipal.safeId(action, "action");
        resourceType = HostedPrincipal.safeId(resourceType, "resourceType");
        resourceId = HostedPrincipal.text(resourceId, "resourceId", 4096);
        Objects.requireNonNull(outcome, "outcome");
        requestId = HostedPrincipal.safeId(requestId, "requestId");
        keyId = HostedPrincipal.safeId(keyId, "keyId");
        previousHash = sha(previousHash, "previousHash");
        hash = sha(hash, "hash");
    }

    private static String sha(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (!SHA256.matcher(normalized).matches()) throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        return normalized;
    }
}
