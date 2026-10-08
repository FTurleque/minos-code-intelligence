package com.minos.hosted;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-H07 : une chaîne d'audit stockée dont la structure a été altérée est refusée, même quand chaque
 * événement porte un HMAC valide. Chaque falsification est re-signée avec la clé de test, pour que ce soit la garde
 * structurelle visée qui refuse, et non l'authentification de l'événement.
 *
 * <p>Le constructeur de {@link HostedTenantState} est la voie par laquelle tout état relu du magasin est construit :
 * ses gardes refusent l'événement non chaîné, l'autre tenant, le lien rompu, l'ordre non croissant et l'ancre
 * incohérente. {@link HostedAuditChain#verify} refuse en plus une séquence croissante mais non contiguë, la seule
 * falsification que le constructeur laisse passer (un événement retiré puis son successeur re-lié).</p>
 */
class HostedAuditChainTamperingTest {
    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private final HostedAuditChain chain = new HostedAuditChain(
            (tenantId, keyId, purpose) -> new SecretKeySpec(KEY, "HmacSHA256"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anIntactThreeEventChainIsAccepted() {
        assertDoesNotThrow(() -> chain.verify(threeEvents()));
    }

    @Test
    void aRemovedMiddleEventIsRefusedAsABrokenChain() {
        List<HostedAuditEvent> events = threeEvents().auditEvents();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(List.of(events.get(0), events.get(2)), 3, HostedAuditEvent.GENESIS_HASH));
        assertEquals("broken audit hash chain", failure.getMessage());
    }

    @Test
    void aRemovedMiddleEventWhoseSuccessorIsRelinkedIsRefusedAsANonContiguousSequence() {
        List<HostedAuditEvent> events = threeEvents().auditEvents();
        HostedAuditEvent first = events.get(0);
        HostedAuditEvent relinked = signed(withLink(events.get(2), events.get(2).sequence(), TENANT, first.hash()));
        HostedTenantState tampered = stateWith(List.of(first, relinked), 3, HostedAuditEvent.GENESIS_HASH);

        SecurityException failure = assertThrows(SecurityException.class, () -> chain.verify(tampered));
        assertEquals("hosted audit sequence is not contiguous", failure.getMessage());
    }

    @Test
    void permutedEventsAreRefused() {
        List<HostedAuditEvent> events = threeEvents().auditEvents();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(List.of(events.get(0), events.get(2), events.get(1)), 2, HostedAuditEvent.GENESIS_HASH));
        assertEquals("broken audit hash chain", failure.getMessage());
    }

    @Test
    void aRelinkedOutOfOrderSequenceIsRefused() {
        List<HostedAuditEvent> events = threeEvents().auditEvents();
        HostedAuditEvent first = events.get(0);
        HostedAuditEvent backwards = signed(withLink(events.get(1), 1, TENANT, first.hash()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(List.of(first, backwards), 1, HostedAuditEvent.GENESIS_HASH));
        assertEquals("audit sequence is not strictly increasing", failure.getMessage());
    }

    @Test
    void anEventOfAnotherTenantIsRefused() {
        List<HostedAuditEvent> events = threeEvents().auditEvents();
        HostedAuditEvent foreign = signed(withLink(events.get(0), 1, OTHER_TENANT, HostedAuditEvent.GENESIS_HASH));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(List.of(foreign), 1, HostedAuditEvent.GENESIS_HASH));
        assertEquals("cross-tenant audit event", failure.getMessage());
    }

    @Test
    void anUnchainedRefusalInsideTheChainIsRefused() {
        HostedAuditEvent unchained = chain.unchainedRefusal(
                emptyTenant(), "owner", "WORKSPACE_CREATE", "WORKSPACE", "new", "req-9", "primary");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(List.of(unchained), 0, HostedAuditEvent.GENESIS_HASH));
        assertEquals("unchained audit event cannot be part of the audit chain", failure.getMessage());
    }

    @Test
    void aSequenceAnchorThatDoesNotMatchTheLastEventIsRefused() {
        List<HostedAuditEvent> events = threeEvents().auditEvents();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(events, 4, HostedAuditEvent.GENESIS_HASH));
        assertEquals("auditSequence does not match last event", failure.getMessage());
    }

    @Test
    void anEmptyInitialChainRequiresTheGenesisAnchor() {
        String notGenesis = "1".repeat(64);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> stateWith(List.of(), 0, notGenesis));
        assertEquals("empty initial audit chain requires genesis anchor", failure.getMessage());
        assertDoesNotThrow(() -> chain.verify(stateWith(List.of(), 0, HostedAuditEvent.GENESIS_HASH)));
    }

    @Test
    void anEmptyChainAfterRetentionIsAcceptedOnItsAnchor() {
        HostedTenantState retained = stateWith(List.of(), 3, threeEvents().auditEvents().getLast().hash());

        assertDoesNotThrow(() -> chain.verify(retained));
    }

    private HostedTenantState threeEvents() {
        HostedTenantState state = emptyTenant();
        for (int index = 1; index <= 3; index++) {
            state = chain.append(state, "owner", "AUDIT_FILL", "TENANT", "fill-" + index,
                    HostedAuditEvent.Outcome.ALLOWED, "req-" + index, "primary", index);
        }
        return state;
    }

    private static HostedAuditEvent withLink(HostedAuditEvent event, long sequence, UUID tenant, String previous) {
        return new HostedAuditEvent(sequence, tenant, event.occurredAt(), event.principalId(), event.action(),
                event.resourceType(), event.resourceId(), event.outcome(), event.requestId(), event.keyId(),
                previous, HostedAuditEvent.GENESIS_HASH, HostedAuditEvent.Chaining.CHAINED);
    }

    /** Same canonical input as the chained HMAC of {@link HostedAuditChain}, signed with the test key. */
    private static HostedAuditEvent signed(HostedAuditEvent event) {
        String canonical = event.sequence() + "\0" + event.tenantId() + "\0" + event.occurredAt().getEpochSecond()
                + "\0" + event.occurredAt().getNano() + "\0" + event.principalId() + "\0" + event.action() + "\0"
                + event.resourceType() + "\0" + event.resourceId() + "\0" + event.outcome() + "\0"
                + event.requestId() + "\0" + event.keyId() + "\0" + event.previousHash();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            String hash = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return new HostedAuditEvent(event.sequence(), event.tenantId(), event.occurredAt(), event.principalId(),
                    event.action(), event.resourceType(), event.resourceId(), event.outcome(), event.requestId(),
                    event.keyId(), event.previousHash(), hash, event.chaining());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HostedTenantState stateWith(List<HostedAuditEvent> events, long auditSequence, String anchor) {
        return new HostedTenantState(TENANT, "Team", "primary", 3, NOW, NOW, HostedRetentionPolicy.defaults(),
                List.of(new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW)), List.of(),
                auditSequence, anchor, events);
    }

    private static HostedTenantState emptyTenant() {
        return new HostedTenantState(TENANT, "Team", "primary", 0, NOW, NOW, HostedRetentionPolicy.defaults(),
                List.of(new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW)), List.of(),
                0, HostedAuditEvent.GENESIS_HASH, List.of());
    }
}
