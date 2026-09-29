package com.minos.hosted;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** HMAC-authenticated, tenant-keyed append-only audit-chain operations. */
final class HostedAuditChain {

    /**
     * HMAC domain tag of unchained refusals. The canonical input of a chained event starts with its
     * decimal sequence, the tagged input of an unchained refusal with a letter: no chained input can
     * equal an unchained one, so an unchained HMAC never authenticates a chain link and conversely.
     */
    static final String UNCHAINED_DOMAIN = "minos-hosted-audit-unchained-refusal-v1";
    /** An unchained refusal has no position in the chain. */
    static final long UNCHAINED_SEQUENCE = 0;

    private final HostedTenantKeyProvider keys;
    private final Clock clock;

    HostedAuditChain(HostedTenantKeyProvider keys, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    HostedTenantState append(
            HostedTenantState state,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            HostedAuditEvent.Outcome outcome,
            String requestId,
            String keyId,
            long targetVersion
    ) {
        if (state.auditEvents().size() >= HostedRetentionPolicy.MAX_AUDIT_EVENTS) {
            throw new IllegalStateException("hosted audit hard capacity reached; apply retention explicitly");
        }
        long sequence = state.auditSequence() + 1;
        Instant occurredAt = clock.instant();
        String previous = head(state);
        String hash = hash(HostedAuditEvent.Chaining.CHAINED, state.tenantId(), sequence, occurredAt, principalId,
                action, resourceType, resourceId, outcome, requestId, keyId, previous);
        HostedAuditEvent event = new HostedAuditEvent(sequence, state.tenantId(), occurredAt, principalId, action,
                resourceType, resourceId, outcome, requestId, keyId, previous, hash);
        return new HostedTenantState(state.tenantId(), state.name(), keyId, targetVersion,
                state.createdAt(), event.occurredAt(), state.retentionPolicy(), state.members(), state.workspaces(),
                event.sequence(), state.auditAnchorHash(), appended(state.auditEvents(), event));
    }

    /**
     * Builds the authenticated refusal delivered to the audit sink when it is deliberately kept off
     * the durable chain. The event is marked {@link HostedAuditEvent.Chaining#UNCHAINED}, has no
     * chain sequence ({@value #UNCHAINED_SEQUENCE}) and is authenticated in a separate HMAC domain,
     * so it can neither collide with the (tenant, sequence) of a chained event nor verify as a link
     * of the chain. Its {@code previousHash} records the chain head observed at refusal time.
     */
    HostedAuditEvent unchainedRefusal(
            HostedTenantState state,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            String keyId
    ) {
        Instant occurredAt = clock.instant();
        String previous = head(state);
        String hash = hash(HostedAuditEvent.Chaining.UNCHAINED, state.tenantId(), UNCHAINED_SEQUENCE, occurredAt,
                principalId, action, resourceType, resourceId, HostedAuditEvent.Outcome.DENIED, requestId, keyId,
                previous);
        return new HostedAuditEvent(UNCHAINED_SEQUENCE, state.tenantId(), occurredAt, principalId, action,
                resourceType, resourceId, HostedAuditEvent.Outcome.DENIED, requestId, keyId, previous, hash,
                HostedAuditEvent.Chaining.UNCHAINED);
    }

    /** Authenticates an unchained refusal in its own HMAC domain; it is never accepted as a chain link. */
    void verifyUnchained(HostedAuditEvent event) {
        if (event.chaining() != HostedAuditEvent.Chaining.UNCHAINED) {
            throw new SecurityException("hosted audit event is not an unchained refusal");
        }
        authenticate(event);
    }

    void verify(HostedTenantState state) {
        String previous = state.auditAnchorHash();
        long expectedSequence = state.auditEvents().isEmpty()
                ? state.auditSequence() + 1
                : state.auditEvents().getFirst().sequence();
        for (HostedAuditEvent event : state.auditEvents()) {
            if (event.chaining() != HostedAuditEvent.Chaining.CHAINED) {
                throw new SecurityException("hosted audit chain contains an unchained event");
            }
            if (event.sequence() != expectedSequence) {
                throw new SecurityException("hosted audit sequence is not contiguous");
            }
            if (!state.tenantId().equals(event.tenantId())) {
                throw new SecurityException("hosted audit event belongs to another tenant");
            }
            if (!previous.equals(event.previousHash())) {
                throw new SecurityException("hosted audit chain is broken");
            }
            authenticate(event);
            previous = event.hash();
            expectedSequence++;
        }
        if (!state.auditEvents().isEmpty() && state.auditSequence() != state.auditEvents().getLast().sequence()) {
            throw new SecurityException("hosted audit sequence anchor does not match the last event");
        }
    }

    private static String head(HostedTenantState state) {
        return state.auditEvents().isEmpty() ? state.auditAnchorHash() : state.auditEvents().getLast().hash();
    }

    private void authenticate(HostedAuditEvent event) {
        String expected = hash(event.chaining(), event.tenantId(), event.sequence(), event.occurredAt(),
                event.principalId(), event.action(), event.resourceType(), event.resourceId(), event.outcome(),
                event.requestId(), event.keyId(), event.previousHash());
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), event.hash().getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("hosted audit event authentication failed");
        }
    }

    /**
     * Chained events keep the canonical input of the persisted history and existing exports; an
     * unchained refusal prefixes it with {@link #UNCHAINED_DOMAIN}, inside the HMAC input (domain
     * separation), not as a marker beside it.
     */
    private String hash(
            HostedAuditEvent.Chaining chaining,
            UUID tenantId,
            long sequence,
            Instant occurredAt,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            HostedAuditEvent.Outcome outcome,
            String requestId,
            String keyId,
            String previous
    ) {
        String chained = sequence + "\0" + tenantId + "\0" + occurredAt.getEpochSecond() + "\0"
                + occurredAt.getNano() + "\0" + principalId + "\0" + action + "\0" + resourceType + "\0"
                + resourceId + "\0" + outcome + "\0" + requestId + "\0" + keyId + "\0" + previous;
        String canonical = chaining == HostedAuditEvent.Chaining.CHAINED
                ? chained
                : UNCHAINED_DOMAIN + "\0" + chained;
        try {
            SecretKey key = keys.resolve(tenantId, keyId, HostedTenantKeyProvider.Purpose.AUDIT_CHAIN);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalStateException exception) {
            throw new SecurityException("hosted audit cryptographic operation failed", exception);
        }
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> updated = new ArrayList<>(values.size() + 1);
        updated.addAll(values);
        updated.add(value);
        return List.copyOf(updated);
    }
}
