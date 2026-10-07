package com.minos.storage.local.store;

import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import com.minos.io.Sha256;
import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B02 : le moteur budgète les refus en octets à partir de {@link HostedAuditEvent#estimatedEncodedBytes()},
 * une majoration qui ne connaît pas le codage du magasin. Ce test la confronte à la taille réellement ajoutée au
 * fichier chiffré par le magasin local : elle ne doit jamais être inférieure, et rester du même ordre.
 */
class HostedEventSizeEstimateTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void theEstimateNeverUnderstatesWhatTheStoreActuallyAddsForARefusalAndStaysClose(@TempDir Path root) throws Exception {
        List<HostedAuditEvent> events = List.of(
                event(1, "viewer", "MEMBER_REVOKE", "PRINCIPAL", "ghost"),
                event(2, "viewer", "WORKSPACE_CREATE", "WORKSPACE", "new"),
                event(3, "a".repeat(128), "TOKEN_ISSUE", "PRINCIPAL", "invalid:" + Sha256.hex("x".repeat(5000))),
                event(4, "owner", "AUDIT_FILL", "TENANT", "é".repeat(100)));

        long actual = bytesAddedBy(root, events);
        long estimate = events.stream().mapToLong(HostedAuditEvent::estimatedEncodedBytes).sum();

        assertTrue(estimate >= actual, "estimate " + estimate + " must not understate the stored " + actual);
        assertTrue(estimate - actual <= 64L * events.size(),
                "estimate " + estimate + " must stay within the framing allowance of the stored " + actual);
    }

    @Test
    void theLocalStoreDeclaresItsByteLimit(@TempDir Path root) throws Exception {
        HostedTenantKeyProvider keys = DerivedTenantKeys.provider();

        assertEquals(OptionalLong.of(FileHostedControlPlaneStore.DEFAULT_MAX_TENANT_BYTES),
                new FileHostedControlPlaneStore(root, keys).tenantByteLimit());
        assertEquals(OptionalLong.of(64L * 1024L),
                new FileHostedControlPlaneStore(root.resolve("small"), keys, 64L * 1024L, new SecureRandom())
                        .tenantByteLimit());
    }

    /** Bytes the store adds to the tenant file when it persists these events, everything else being equal. */
    private static long bytesAddedBy(Path root, List<HostedAuditEvent> events) throws Exception {
        HostedTenantKeyProvider keys = DerivedTenantKeys.provider();
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, keys);
        UUID tenant = events.getFirst().tenantId();
        store.create(state(tenant, 0, List.of()));
        long before = Files.size(root.resolve(tenant + ".mht"));
        store.save(state(tenant, 1, events), 0);
        return Files.size(root.resolve(tenant + ".mht")) - before;
    }

    private static HostedTenantState state(UUID tenant, long version, List<HostedAuditEvent> events) {
        return new HostedTenantState(
                tenant, "Team", "primary", version, NOW, NOW, HostedRetentionPolicy.defaults(),
                List.of(new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW)), List.of(),
                events.isEmpty() ? 0 : events.getLast().sequence(), HostedAuditEvent.GENESIS_HASH, chained(events));
    }

    /** Events carry the hash of their predecessor, as the tenant state requires. */
    private static List<HostedAuditEvent> chained(List<HostedAuditEvent> events) {
        List<HostedAuditEvent> linked = new ArrayList<>();
        String previous = HostedAuditEvent.GENESIS_HASH;
        for (HostedAuditEvent event : events) {
            HostedAuditEvent link = new HostedAuditEvent(
                    event.sequence(), event.tenantId(), event.occurredAt(), event.principalId(), event.action(),
                    event.resourceType(), event.resourceId(), event.outcome(), event.requestId(), event.keyId(),
                    previous, event.hash());
            linked.add(link);
            previous = link.hash();
        }
        return linked;
    }

    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static HostedAuditEvent event(
            long sequence, String principal, String action, String type, String resourceId) {
        return new HostedAuditEvent(
                sequence, TENANT, NOW, principal, action, type, resourceId, HostedAuditEvent.Outcome.DENIED,
                "request-" + sequence, "primary", HostedAuditEvent.GENESIS_HASH, Sha256.hex("event-" + sequence));
    }
}
