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
        String previous = state.auditEvents().isEmpty()
                ? state.auditAnchorHash() : state.auditEvents().getLast().hash();
        Instant occurredAt = clock.instant();
        String hash = hash(state.tenantId(), sequence, occurredAt, principalId, action, resourceType,
                resourceId, outcome, requestId, keyId, previous);
        HostedAuditEvent event = new HostedAuditEvent(sequence, state.tenantId(), occurredAt, principalId, action,
                resourceType, resourceId, outcome, requestId, keyId, previous, hash);
        return new HostedTenantState(state.tenantId(), state.name(), keyId, targetVersion,
                state.createdAt(), occurredAt, state.retentionPolicy(), state.members(), state.workspaces(),
                sequence, state.auditAnchorHash(), appended(state.auditEvents(), event));
    }

    void verify(HostedTenantState state) {
        String previous = state.auditAnchorHash();
        long expectedSequence = state.auditEvents().isEmpty()
                ? state.auditSequence() + 1
                : state.auditEvents().getFirst().sequence();
        for (HostedAuditEvent event : state.auditEvents()) {
            if (event.sequence() != expectedSequence) {
                throw new SecurityException("hosted audit sequence is not contiguous");
            }
            if (!state.tenantId().equals(event.tenantId())) {
                throw new SecurityException("hosted audit event belongs to another tenant");
            }
            if (!previous.equals(event.previousHash())) {
                throw new SecurityException("hosted audit chain is broken");
            }
            String expected = hash(event.tenantId(), event.sequence(), event.occurredAt(), event.principalId(),
                    event.action(), event.resourceType(), event.resourceId(), event.outcome(), event.requestId(),
                    event.keyId(), event.previousHash());
            if (!java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII), event.hash().getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("hosted audit event authentication failed");
            }
            previous = event.hash();
            expectedSequence++;
        }
        if (!state.auditEvents().isEmpty() && state.auditSequence() != state.auditEvents().getLast().sequence()) {
            throw new SecurityException("hosted audit sequence anchor does not match the last event");
        }
    }

    private String hash(
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
        String canonical = sequence + "\0" + tenantId + "\0" + occurredAt.getEpochSecond() + "\0"
                + occurredAt.getNano() + "\0" + principalId + "\0" + action + "\0" + resourceType + "\0"
                + resourceId + "\0" + outcome + "\0" + requestId + "\0" + keyId + "\0" + previous;
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
