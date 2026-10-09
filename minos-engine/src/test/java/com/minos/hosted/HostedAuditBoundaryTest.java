package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-H07 : bornes exactes du plan de contrôle. La capacité dure de la chaîne refuse l'ajout exactement à
 * {@link HostedRetentionPolicy#MAX_AUDIT_EVENTS} événements, ni avant ni après ; un refus est encore chaîné quand
 * les octets des refus atteignent exactement le quart de la limite déclarée par le magasin, et ne l'est plus un octet
 * au-delà.
 */
class HostedAuditBoundaryTest {

    @Test
    void theHardCapacityRefusesTheAppendExactlyAtMaxAuditEvents() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        harness.bootstrapOwner();
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        HostedTenantState belowCapacity = HostedControlPlaneTestSupport.extendedTo(harness.state(), chain,
                HostedRetentionPolicy.MAX_AUDIT_EVENTS - 1, HostedAuditEvent.Outcome.ALLOWED);

        HostedTenantState atCapacity = chain.append(belowCapacity, "owner", "AUDIT_FILL", "TENANT", "last",
                HostedAuditEvent.Outcome.ALLOWED, "last", belowCapacity.keyId(), belowCapacity.version());
        assertEquals(HostedRetentionPolicy.MAX_AUDIT_EVENTS, atCapacity.auditEvents().size());

        String keyId = atCapacity.keyId();
        long version = atCapacity.version();
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> chain.append(
                atCapacity, "owner", "AUDIT_FILL", "TENANT", "beyond",
                HostedAuditEvent.Outcome.ALLOWED, "beyond", keyId, version));
        assertEquals("hosted audit hard capacity reached; apply retention explicitly", refused.getMessage());
    }

    @Test
    void aRefusalIsChainedWhenItsBytesReachExactlyAQuarterOfTheLimitAndNotOneByteBeyond() throws Exception {
        long refusalBytes = firstRefusalBytes(Long.MAX_VALUE / 2);

        Outcome atTheShare = firstRefusal(refusalBytes * HostedAuthorizationService.DENIED_BYTES_SHARE_DENOMINATOR);
        assertEquals(1, atTheShare.chainedDenials(), "exactly a quarter of the limit: the refusal is chained");
        assertEquals(0, atTheShare.unchainedDenials());

        Outcome beyondTheShare = firstRefusal(
                refusalBytes * HostedAuthorizationService.DENIED_BYTES_SHARE_DENOMINATOR - 1);
        assertEquals(0, beyondTheShare.chainedDenials(), "one byte beyond the quarter: the refusal is not chained");
        assertEquals(1, beyondTheShare.unchainedDenials());
    }

    private static long firstRefusalBytes(long limit) throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        refuseOnce(harness, limit);
        return harness.state().auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED)
                .mapToLong(HostedAuditEvent::estimatedEncodedBytes)
                .sum();
    }

    private static Outcome firstRefusal(long limit) throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        refuseOnce(harness, limit);
        long chained = harness.state().auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED).count();
        return new Outcome(chained, harness.sink().unchained.size());
    }

    private static void refuseOnce(HostedControlPlaneTestSupport.Harness harness, long limit) throws Exception {
        HostedControlPlaneService service = new HostedControlPlaneService(
                new LimitedStore(harness.store(), limit), new HmacHostedIdentityProvider(harness.keys()),
                harness.keys(), (project, snapshot) -> { }, harness.sink(), harness.clock());
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        assertThrows(SecurityException.class, () -> service.createWorkspace(viewer, "denied-0", "X"));
    }

    private record Outcome(long chainedDenials, long unchainedDenials) {
    }

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
