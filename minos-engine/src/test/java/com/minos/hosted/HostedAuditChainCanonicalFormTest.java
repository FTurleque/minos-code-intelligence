package com.minos.hosted;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-B01 : le HMAC d'un événement d'audit couvre exactement les valeurs persistées et exportées, et
 * l'entrée du HMAC d'un événement déjà canonique reste identique octet pour octet à celle des chaînes produites
 * avant ce changement.
 *
 * <p>Les deux vecteurs de référence ont été calculés hors du code Java, en Python, à partir de la définition de
 * l'entrée canonique ({@code séquence NUL locataire NUL secondes NUL nanosecondes NUL principal NUL action NUL
 * type NUL ressource NUL résultat NUL requête NUL clé NUL précédent}, préfixée par le domaine d'un refus non
 * chaîné) : ils ne dépendent donc pas de l'implémentation qu'ils protègent.</p>
 */
class HostedAuditChainCanonicalFormTest {
    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String CHAINED_REFERENCE_HMAC =
            "64d27d9b206301ea16ff4edb7f256dd835dc8aea5785c366b3a12fb551dcce1c";
    private static final String UNCHAINED_REFERENCE_HMAC =
            "b47d691dedbc5f590f8c74e61fa9ab0e1634adbf9525578132571bb331dcb69a";

    private final HostedAuditChain chain = new HostedAuditChain(
            (tenantId, keyId, purpose) -> new SecretKeySpec(KEY, "HmacSHA256"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aCanonicalChainedEventKeepsTheHmacOfEveryPreviousVersion() {
        HostedTenantState appended = chain.append(
                emptyTenant(), "owner", "AUDIT_FILL", "TENANT", "fill",
                HostedAuditEvent.Outcome.ALLOWED, "req-1", "primary", 1);

        assertEquals(CHAINED_REFERENCE_HMAC, appended.auditEvents().getLast().hash());
        assertDoesNotThrow(() -> chain.verify(appended));
    }

    @Test
    void aCanonicalUnchainedRefusalKeepsTheHmacOfEveryPreviousVersion() {
        HostedAuditEvent refusal = chain.unchainedRefusal(
                emptyTenant(), "viewer", "WORKSPACE_CREATE", "WORKSPACE", "new", "req-2", "primary");

        assertEquals(UNCHAINED_REFERENCE_HMAC, refusal.hash());
        assertDoesNotThrow(() -> chain.verifyUnchained(refusal));
    }

    @Test
    void everyFieldOfAChainedEventBuiltByTheChainIsAFixedPointOfCanonicalization() {
        for (String raw : List.of(" ghost", "\tghost", "ghost ", "  ghost  ", "\u0001ghost")) {
            HostedTenantState appended = chain.append(
                    emptyTenant(), "owner", "AUDIT_FILL", "TENANT", raw,
                    HostedAuditEvent.Outcome.DENIED, "req-1", "primary", 1);
            HostedAuditEvent event = appended.auditEvents().getLast();

            assertEquals(rebuilt(event), event, "rebuilding from the accessors must give the same event for " + raw);
            assertEquals("ghost", event.resourceId());
            assertDoesNotThrow(() -> chain.verify(appended), "the chain must stay verifiable for " + raw);
        }
    }

    @Test
    void anUnchainedRefusalWithANonCanonicalResourceIdIsAuthenticatedOnItsPersistedValue() {
        for (String raw : List.of(" ghost", "\tghost", "ghost ")) {
            HostedAuditEvent refusal = chain.unchainedRefusal(
                    emptyTenant(), "viewer", "MEMBER_GRANT", "PRINCIPAL", raw, "req-3", "primary");

            assertEquals("ghost", refusal.resourceId());
            assertDoesNotThrow(() -> chain.verifyUnchained(refusal), "unchained refusal for " + raw);
        }
    }

    @Test
    void anEventAlteredAfterTheFactIsStillRejected() {
        HostedTenantState appended = chain.append(
                emptyTenant(), "owner", "AUDIT_FILL", "TENANT", "fill",
                HostedAuditEvent.Outcome.ALLOWED, "req-1", "primary", 1);
        HostedAuditEvent genuine = appended.auditEvents().getLast();

        HostedAuditEvent otherField = new HostedAuditEvent(
                genuine.sequence(), genuine.tenantId(), genuine.occurredAt(), genuine.principalId(), genuine.action(),
                genuine.resourceType(), "other", genuine.outcome(), genuine.requestId(), genuine.keyId(),
                genuine.previousHash(), genuine.hash());
        assertNotEquals(genuine, otherField);
        assertAuthenticationFails(withEvent(appended, otherField));

        String flipped = (genuine.hash().charAt(0) == '0' ? "1" : "0") + genuine.hash().substring(1);
        HostedAuditEvent otherHash = new HostedAuditEvent(
                genuine.sequence(), genuine.tenantId(), genuine.occurredAt(), genuine.principalId(), genuine.action(),
                genuine.resourceType(), genuine.resourceId(), genuine.outcome(), genuine.requestId(), genuine.keyId(),
                genuine.previousHash(), flipped);
        assertAuthenticationFails(withEvent(appended, otherHash));
    }

    private void assertAuthenticationFails(HostedTenantState state) {
        SecurityException failure = assertThrows(SecurityException.class, () -> chain.verify(state));
        assertEquals("hosted audit event authentication failed", failure.getMessage());
    }

    private static HostedAuditEvent rebuilt(HostedAuditEvent event) {
        return new HostedAuditEvent(
                event.sequence(), event.tenantId(), event.occurredAt(), event.principalId(), event.action(),
                event.resourceType(), event.resourceId(), event.outcome(), event.requestId(), event.keyId(),
                event.previousHash(), event.hash(), event.chaining());
    }

    private static HostedTenantState withEvent(HostedTenantState state, HostedAuditEvent event) {
        List<HostedAuditEvent> events = new ArrayList<>(state.auditEvents());
        events.set(events.size() - 1, event);
        return new HostedTenantState(
                state.tenantId(), state.name(), state.keyId(), state.version(), state.createdAt(), state.updatedAt(),
                state.retentionPolicy(), state.members(), state.workspaces(), state.auditSequence(),
                state.auditAnchorHash(), events);
    }

    private static HostedTenantState emptyTenant() {
        return new HostedTenantState(
                TENANT, "Team", "primary", 0, NOW, NOW, HostedRetentionPolicy.defaults(),
                List.of(new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW)), List.of(),
                0, HostedAuditEvent.GENESIS_HASH, List.of());
    }
}
