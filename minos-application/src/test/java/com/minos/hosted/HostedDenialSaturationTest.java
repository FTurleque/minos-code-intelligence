package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S2: looping refusals must never starve authorized mutations, while every refusal stays traceable. */
class HostedDenialSaturationTest {
    /** Chained refusals allowed per (tenant, principal) inside one throttle window. */
    private static final int CHAINED_REFUSALS_PER_WINDOW = 10;

    @Test
    void refusalsCannotExhaustHardCapacityForAuthorizedMutations() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        harness.store().values.put(harness.tenant(),
                extendedTo(harness.state(), chain, HostedRetentionPolicy.MAX_AUDIT_EVENTS - 10));

        for (int index = 0; index < 10; index++) {
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class,
                    () -> harness.service().createWorkspace(viewer, requestId, "Forbidden"));
        }

        SharedWorkspace workspace = harness.service().createWorkspace(owner, "allowed-1", "Shared");
        HostedTenantState state = harness.state();
        assertEquals(workspace.workspaceId(), state.workspaces().getFirst().workspaceId());
        assertEquals(HostedAuditEvent.Outcome.ALLOWED, state.auditEvents().getLast().outcome());
        assertTrue(state.auditEvents().size() < HostedRetentionPolicy.MAX_AUDIT_EVENTS);
        chain.verify(state);
    }

    @Test
    void refusalsStopBeingChainedAtTheDeniedCapacityEvenAcrossProcesses() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedRetentionPolicy policy = new HostedRetentionPolicy(100, 365, 90);
        harness.service().setRetention(owner, "policy-1", policy);
        int baseline = harness.state().auditEvents().size();
        int deniedCapacity = policy.maxAuditEvents() - policy.maxAuditEvents() / 10;

        int refusals = 0;
        for (int process = 0; process < 12; process++) {
            HostedControlPlaneService service = harness.anotherProcess();
            for (int index = 0; index < CHAINED_REFUSALS_PER_WINDOW; index++) {
                String requestId = "denied-" + process + "-" + index;
                assertThrows(SecurityException.class,
                        () -> service.createWorkspace(viewer, requestId, "Forbidden"));
                refusals++;
            }
        }

        HostedTenantState saturated = harness.state();
        assertTrue(saturated.auditEvents().size() <= deniedCapacity,
                "chained refusals exceeded the denied capacity: " + saturated.auditEvents().size());
        long chainedDenials = saturated.auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED).count();
        assertEquals(deniedCapacity - baseline, chainedDenials);
        assertEquals(refusals - chainedDenials, harness.sink().unchained.size());
        assertTrue(harness.sink().unchained.stream()
                .allMatch(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED
                        && event.principalId().equals("viewer")));

        harness.service().createWorkspace(owner, "allowed-1", "Shared");
        HostedTenantState state = harness.state();
        assertEquals(HostedAuditEvent.Outcome.ALLOWED, state.auditEvents().getLast().outcome());
        assertEquals(saturated.version() + 1, state.version());
        new HostedAuditChain(harness.keys(), harness.clock()).verify(state);
    }

    @Test
    void isolatedRefusalRemainsChainedDeniedAndPublished() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        long version = harness.state().version();

        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "denied-1", "Forbidden"));

        HostedAuditEvent last = harness.state().auditEvents().getLast();
        assertEquals(HostedAuditEvent.Outcome.DENIED, last.outcome());
        assertEquals("denied-1", last.requestId());
        assertEquals(version + 1, harness.state().version());
        assertEquals(last, harness.sink().events.getLast());
        assertTrue(harness.sink().unchained.isEmpty());
    }

    @Test
    void governanceRefusalsShareTheChainedRefusalBudget() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);
        long version = harness.state().version();

        int refusals = 3 * CHAINED_REFUSALS_PER_WINDOW;
        for (int index = 0; index < refusals; index++) {
            String requestId = "escalate-" + index;
            String target = "mallory-" + index;
            assertThrows(SecurityException.class,
                    () -> harness.service().grantMember(admin, requestId, target, "Mallory", HostedRole.OWNER));
        }

        HostedTenantState state = harness.state();
        long chained = state.auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED
                        && event.action().equals("MEMBER_GRANT") && event.principalId().equals("admin"))
                .count();
        assertEquals(CHAINED_REFUSALS_PER_WINDOW, chained);
        assertEquals(version + CHAINED_REFUSALS_PER_WINDOW, state.version());
        assertEquals(refusals - CHAINED_REFUSALS_PER_WINDOW, harness.sink().unchained.size());
        assertTrue(harness.sink().unchained.stream().allMatch(event -> event.action().equals("MEMBER_GRANT")));
        assertTrue(state.members().stream().noneMatch(member -> member.principalId().startsWith("mallory")));

        harness.service().grantMember(admin, "grant-helper", "helper", "Helper", HostedRole.VIEWER);
        harness.service().createWorkspace(owner, "allowed-1", "Shared");
        assertEquals(version + CHAINED_REFUSALS_PER_WINDOW + 2, harness.state().version());
    }

    @Test
    void refusalBudgetIsPerPrincipalNotPerActionOrResource() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        String auditor = harness.grantAndIssue(owner, "auditor", HostedRole.AUDITOR);
        long version = harness.state().version();

        int refusals = 3 * CHAINED_REFUSALS_PER_WINDOW;
        for (int index = 0; index < refusals; index++) {
            String requestId = "denied-" + index;
            int variant = index % 5;
            assertThrows(SecurityException.class, () -> {
                switch (variant) {
                    case 0 -> harness.service().createWorkspace(viewer, requestId, "space-" + requestId);
                    case 1 -> harness.service().bindProject(
                            viewer, requestId, UUID.randomUUID(), UUID.randomUUID(), "snapshot-" + requestId);
                    case 2 -> harness.service().setRetention(viewer, requestId, HostedRetentionPolicy.defaults());
                    case 3 -> harness.service().revokeMember(viewer, requestId, "owner");
                    default -> harness.service().issueToken(viewer, requestId, "owner", Duration.ofHours(1));
                }
            });
        }
        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(auditor, "auditor-denied", "Forbidden"));

        HostedTenantState state = harness.state();
        long viewerChained = state.auditEvents().stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED
                        && event.principalId().equals("viewer"))
                .count();
        assertEquals(CHAINED_REFUSALS_PER_WINDOW, viewerChained);
        assertEquals("auditor", state.auditEvents().getLast().principalId());
        assertEquals(HostedAuditEvent.Outcome.DENIED, state.auditEvents().getLast().outcome());
        assertEquals(version + CHAINED_REFUSALS_PER_WINDOW + 1, state.version());
        assertEquals(refusals - CHAINED_REFUSALS_PER_WINDOW, harness.sink().unchained.size());
        assertTrue(harness.sink().unchained.stream()
                .allMatch(event -> event.principalId().equals("viewer")
                        && event.outcome() == HostedAuditEvent.Outcome.DENIED
                        && event.requestId().startsWith("denied-")));
        new HostedAuditChain(harness.keys(), harness.clock()).verify(state);
    }

    @Test
    void refusalBudgetSlidesWithTime() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        for (int index = 0; index <= CHAINED_REFUSALS_PER_WINDOW; index++) {
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class,
                    () -> harness.service().createWorkspace(viewer, requestId, "Forbidden"));
        }
        assertEquals(1, harness.sink().unchained.size());

        harness.clock().advance(Duration.ofSeconds(61));
        assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(viewer, "denied-later", "Forbidden"));

        assertEquals(1, harness.sink().unchained.size());
        assertEquals("denied-later", harness.state().auditEvents().getLast().requestId());
    }

    /** Extends the persisted chain with authentic filler events using a one-event window (O(n) build). */
    private static HostedTenantState extendedTo(HostedTenantState state, HostedAuditChain chain, int size) {
        List<HostedAuditEvent> events = new ArrayList<>(state.auditEvents());
        HostedTenantState window = state;
        while (events.size() < size) {
            HostedAuditEvent last = window.auditEvents().getLast();
            HostedTenantState oneEvent = new HostedTenantState(
                    state.tenantId(), state.name(), state.keyId(), state.version(), state.createdAt(),
                    window.updatedAt(), state.retentionPolicy(), state.members(), state.workspaces(),
                    window.auditSequence(), last.previousHash(), List.of(last));
            window = chain.append(oneEvent, "owner", "AUDIT_FILL", "TENANT", "fill",
                    HostedAuditEvent.Outcome.ALLOWED, "fill-" + events.size(), state.keyId(), state.version());
            events.add(window.auditEvents().getLast());
        }
        return new HostedTenantState(
                state.tenantId(), state.name(), state.keyId(), state.version(), state.createdAt(),
                window.updatedAt(), state.retentionPolicy(), state.members(), state.workspaces(),
                events.getLast().sequence(), state.auditAnchorHash(), events);
    }
}
