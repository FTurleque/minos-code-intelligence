package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B02, côté moteur : le budget d'octets des refus chaînés est dérivé de la limite que le magasin déclare
 * par son port ; un magasin qui n'en déclare pas reste borné par le seul nombre de refus.
 */
class HostedDenialByteBudgetPortTest {
    private static final int DECLARED_LIMIT_BYTES = 8 * 1024;

    @Test
    void aStoreThatDeclaresNoByteLimitKeepsTheCountOnlyBehavior() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        assertTrue(harness.store().tenantByteLimit().isEmpty());
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        for (int index = 0; index < 30; index++) {
            harness.clock().advance(Duration.ofSeconds(7));
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class, () -> harness.service().createWorkspace(viewer, requestId, "X"));
        }

        assertEquals(30, harness.state().auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED).count());
        assertTrue(harness.sink().unchained.isEmpty(), "no byte limit: every refusal within the count budget is chained");
    }

    @Test
    void aDeclaredByteLimitCapsTheBytesOfChainedRefusalsAtAQuarterOfIt() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        LimitedStore store = new LimitedStore(harness.store(), DECLARED_LIMIT_BYTES);
        HostedControlPlaneService service = new HostedControlPlaneService(
                store, new HmacHostedIdentityProvider(harness.keys()), harness.keys(),
                (project, snapshot) -> { }, harness.sink(), harness.clock());
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        for (int index = 0; index < 60; index++) {
            harness.clock().advance(Duration.ofSeconds(7));
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class, () -> service.createWorkspace(viewer, requestId, "X"));
        }

        long chainedBytes = harness.state().auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED)
                .mapToLong(HostedAuditEvent::estimatedEncodedBytes)
                .sum();
        assertTrue(chainedBytes > 0, "refusals are chained while the byte budget lasts");
        assertTrue(chainedBytes <= DECLARED_LIMIT_BYTES / HostedAuthorizationService.DENIED_BYTES_SHARE_DENOMINATOR,
                "chained refusals stay within their share of the declared limit: " + chainedBytes);
        assertFalse(harness.sink().unchained.isEmpty(), "refusals beyond the budget are delivered unchained");
        assertTrue(service.audit(owner, 10_000).stream().anyMatch(event ->
                event.outcome() == HostedAuditEvent.Outcome.ALLOWED), "authorized events stay readable");
    }

    /** The in-memory store, declaring a per-tenant byte limit (the engine never enforces it itself). */
    private record LimitedStore(HostedControlPlaneStore delegate, long limit) implements HostedControlPlaneStore {
        @Override
        public void create(HostedTenantState state) throws IOException {
            delegate.create(state);
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) throws IOException {
            return delegate.find(tenantId);
        }

        @Override
        public void save(HostedTenantState state, long expectedVersion) throws IOException {
            delegate.save(state, expectedVersion);
        }

        @Override
        public OptionalLong tenantByteLimit() {
            return OptionalLong.of(limit);
        }
    }
}
