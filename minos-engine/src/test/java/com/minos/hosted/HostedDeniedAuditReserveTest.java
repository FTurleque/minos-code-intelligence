package com.minos.hosted;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S12: the chained-refusal reserve counts the refusals already chained ({@code DENIED}), not the
 * total size of the chain, so legitimate authorized traffic can never silence the first refusal of
 * a real attack; the hard capacity stays out of reach of refusals.
 */
class HostedDeniedAuditReserveTest {

    @Test
    void firstAttackRefusalIsChainedAfterNinetyPercentOfAuthorizedEvents() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        HostedRetentionPolicy policy = harness.state().retentionPolicy();
        harness.store().values.put(harness.tenant(), HostedControlPlaneTestSupport.extendedTo(
                harness.state(), chain, policy.deniedAuditCapacity(), HostedAuditEvent.Outcome.ALLOWED));
        long version = harness.state().version();

        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-1", "Forbidden"));

        HostedTenantState state = harness.state();
        HostedAuditEvent last = state.auditEvents().getLast();
        assertEquals(HostedAuditEvent.Outcome.DENIED, last.outcome());
        assertEquals("attack-1", last.requestId());
        assertEquals(version + 1, state.version());
        assertEquals(last, harness.sink().events.getLast());
        assertTrue(harness.sink().unchained.isEmpty(), "the first attack refusal was only journaled");
        chain.verify(state);
    }

    @Test
    void firstAttackRefusalIsChainedWhileAuthorizedEventsAwaitAnExplicitRetention() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        HostedRetentionPolicy policy = harness.state().retentionPolicy();
        harness.store().values.put(harness.tenant(), HostedControlPlaneTestSupport.extendedTo(
                harness.state(), chain, policy.maxAuditEvents() + 500, HostedAuditEvent.Outcome.ALLOWED));

        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-1", "Forbidden"));

        assertEquals("attack-1", harness.state().auditEvents().getLast().requestId());
        assertTrue(harness.sink().unchained.isEmpty());
    }

    @Test
    void reserveCountsOnlyChainedRefusals() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedRetentionPolicy policy = new HostedRetentionPolicy(100, 365, 90);
        harness.service().setRetention(owner, "policy-1", policy);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        int baseline = harness.state().auditEvents().size();
        HostedTenantState denied = HostedControlPlaneTestSupport.extendedTo(
                harness.state(), chain, baseline + policy.deniedAuditCapacity() - 1, HostedAuditEvent.Outcome.DENIED);
        harness.store().values.put(harness.tenant(), HostedControlPlaneTestSupport.extendedTo(
                denied, chain, denied.auditEvents().size() + 60, HostedAuditEvent.Outcome.ALLOWED));

        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-1", "Forbidden"));
        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-2", "Forbidden"));

        HostedTenantState state = harness.state();
        assertEquals(policy.deniedAuditCapacity(), deniedCount(state));
        assertEquals("attack-1", state.auditEvents().getLast().requestId());
        assertEquals(1, harness.sink().unchained.size());
        assertEquals("attack-2", harness.sink().unchained.getFirst().requestId());
        chain.verify(state);
    }

    @Test
    void explicitRetentionReleasesTheReserveAndKeepsTheChainContiguous() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedRetentionPolicy policy = new HostedRetentionPolicy(100, 365, 90);
        harness.service().setRetention(owner, "policy-1", policy);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        int baseline = harness.state().auditEvents().size();
        HostedTenantState denied = HostedControlPlaneTestSupport.extendedTo(
                harness.state(), chain, baseline + policy.deniedAuditCapacity(), HostedAuditEvent.Outcome.DENIED);
        harness.store().values.put(harness.tenant(), HostedControlPlaneTestSupport.extendedTo(
                denied, chain, denied.auditEvents().size() + 60, HostedAuditEvent.Outcome.ALLOWED));

        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "saturated", "Forbidden"));
        assertEquals(1, harness.sink().unchained.size(), "the reserve is saturated before retention");

        harness.service().applyRetention(owner, "retention-1");
        HostedTenantState retained = harness.state();
        assertTrue(deniedCount(retained) < policy.deniedAuditCapacity());
        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "after-retention", "Forbidden"));

        HostedTenantState state = harness.state();
        assertEquals("after-retention", state.auditEvents().getLast().requestId());
        assertEquals(1, harness.sink().unchained.size());
        for (int index = 1; index < state.auditEvents().size(); index++) {
            assertEquals(state.auditEvents().get(index - 1).sequence() + 1, state.auditEvents().get(index).sequence());
        }
        chain.verify(state);
    }

    @Test
    void refusalsNeverConsumeTheAuthorizedHeadroomBelowTheHardCapacity() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        HostedRetentionPolicy policy = harness.state().retentionPolicy();
        int lastChainableSize = HostedRetentionPolicy.MAX_AUDIT_EVENTS - policy.maxAuditEvents() / 10 - 1;
        harness.store().values.put(harness.tenant(), HostedControlPlaneTestSupport.extendedTo(
                harness.state(), chain, lastChainableSize, HostedAuditEvent.Outcome.ALLOWED));

        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-1", "Forbidden"));
        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-2", "Forbidden"));

        HostedTenantState state = harness.state();
        assertEquals(lastChainableSize + 1, state.auditEvents().size());
        assertEquals("attack-1", state.auditEvents().getLast().requestId());
        assertEquals(1, harness.sink().unchained.size());
        assertEquals("attack-2", harness.sink().unchained.getFirst().requestId());
        harness.service().createWorkspace(owner, "allowed-1", "Shared");
        assertEquals(HostedAuditEvent.Outcome.ALLOWED, harness.state().auditEvents().getLast().outcome());
    }

    @Test
    void refusalOnAChainAtHardCapacityIsStillEnforcedAndDeliveredUnchained() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        HostedTenantState full = HostedControlPlaneTestSupport.extendedTo(
                harness.state(), chain, HostedRetentionPolicy.MAX_AUDIT_EVENTS, HostedAuditEvent.Outcome.ALLOWED);
        harness.store().values.put(harness.tenant(), full);

        SecurityException refusal = assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "attack-1", "Forbidden"));

        assertTrue(refusal.getMessage().contains("hosted permission denied"), refusal.getMessage());
        assertEquals(full, harness.state(), "a refusal at hard capacity must not touch the tenant state");
        assertEquals(1, harness.sink().unchained.size());
        assertEquals("attack-1", harness.sink().unchained.getFirst().requestId());
    }

    private static long deniedCount(HostedTenantState state) {
        return state.auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED)
                .count();
    }
}
