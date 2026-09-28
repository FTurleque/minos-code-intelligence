package com.minos.hosted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Security audit entry, authenticated with the tenant audit key.
 *
 * <p>A {@link Chaining#CHAINED} event is a link of the durable tenant chain: its sequence is its
 * position (from 1) and {@code previousHash} links it to its predecessor. A
 * {@link Chaining#UNCHAINED} event is a refusal deliberately kept off the chain (refusal reserve or
 * per-principal budget exhausted): it has no chain position ({@code sequence == 0}),
 * {@code previousHash} only records the chain head observed when the refusal happened, and its HMAC
 * is computed in a separate domain, so it can never authenticate as a link of the chain. Such an
 * event is identified by its {@code hash}, not by a sequence.</p>
 */
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
        String hash,
        Chaining chaining
) {
    public static final String GENESIS_HASH = "0".repeat(64);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public enum Outcome { ALLOWED, DENIED }

    /** Whether the event is a link of the durable tenant chain or a refusal kept off it. */
    public enum Chaining { CHAINED, UNCHAINED }

    public HostedAuditEvent {
        Objects.requireNonNull(chaining, "chaining");
        if (chaining == Chaining.CHAINED && sequence < 1) {
            throw new IllegalArgumentException("audit sequence must be positive");
        }
        if (chaining == Chaining.UNCHAINED && sequence != 0) {
            throw new IllegalArgumentException("an unchained audit event has no chain sequence");
        }
        if (chaining == Chaining.UNCHAINED && outcome != Outcome.DENIED) {
            throw new IllegalArgumentException("only refusals can be kept off the audit chain");
        }
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

    /** A link of the durable chain, as persisted and exported before unchained refusals were marked. */
    public HostedAuditEvent(
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
        this(sequence, tenantId, occurredAt, principalId, action, resourceType, resourceId, outcome, requestId,
                keyId, previousHash, hash, Chaining.CHAINED);
    }

    private static String sha(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (!SHA256.matcher(normalized).matches()) throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        return normalized;
    }
}
