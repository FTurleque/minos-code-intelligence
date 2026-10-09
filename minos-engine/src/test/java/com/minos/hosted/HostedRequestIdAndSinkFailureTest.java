package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H14 : l'identifiant de requête n'est validé qu'après l'autorisation, comme les autres identifiants ; un
 * appelant sans droit qui envoie un identifiant de requête invalide est refusé et tracé. MINOS-AUD-H15 : une
 * exception d'exécution d'un puits d'audit externe ne transforme jamais une mutation déjà persistée en échec, ni un
 * refus en autre chose qu'un refus.
 */
class HostedRequestIdAndSinkFailureTest {

    @Test
    void aCallerWithoutRightsAndAnInvalidRequestIdIsRefusedAndTraced() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedControlPlaneService service = harness.service();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        int before = harness.state().auditEvents().size();

        assertThrows(SecurityException.class, () -> service.createWorkspace(viewer, "bad request", "X"));

        assertEquals(before + 1, harness.state().auditEvents().size(), "the refusal must leave a chained trace");
        HostedAuditEvent refusal = harness.state().auditEvents().getLast();
        assertEquals(HostedAuditEvent.Outcome.DENIED, refusal.outcome());
        assertTrue(refusal.requestId().startsWith(HostedAuthorizationService.INVALID_RESOURCE_PREFIX),
                "the raw invalid request id is never recorded: " + refusal.requestId());
    }

    @Test
    void anAuthorizedCallerWithAnInvalidRequestIdStillGetsAnInvalidRequestWithoutEvent() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedControlPlaneService service = harness.service();
        String owner = harness.bootstrapOwner();
        HostedTenantState before = harness.state();

        assertThrows(IllegalArgumentException.class, () -> service.createWorkspace(owner, "bad request", "X"));

        assertEquals(before, harness.state(), "no event, no version change");
    }

    @Test
    void aSinkRuntimeFailureNeverTurnsACommittedMutationIntoAFailure() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        HostedControlPlaneService service = new HostedControlPlaneService(
                harness.store(), new HmacHostedIdentityProvider(harness.keys()), harness.keys(),
                (project, snapshot) -> { }, failingSink(), harness.clock());
        long versionBefore = harness.state().version();

        assertDoesNotThrow(() -> service.createWorkspace(owner, "create-1", "Workspace"));

        assertEquals(versionBefore + 1, harness.state().version(), "the mutation is committed");
    }

    @Test
    void aSinkRuntimeFailureNeverTurnsARefusalIntoAnotherError() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        HostedControlPlaneService service = new HostedControlPlaneService(
                harness.store(), new HmacHostedIdentityProvider(harness.keys()), harness.keys(),
                (project, snapshot) -> { }, failingSink(), harness.clock());
        harness.clock().advance(Duration.ofSeconds(7));

        assertThrows(SecurityException.class, () -> service.createWorkspace(viewer, "denied-1", "X"));
    }

    private static HostedAuditSink failingSink() {
        return new HostedAuditSink() {
            @Override
            public void publish(HostedAuditEvent event) {
                throw new IllegalStateException("sink down " + UUID.randomUUID());
            }

            @Override
            public void publishUnchained(HostedAuditEvent event) {
                throw new IllegalStateException("sink down " + UUID.randomUUID());
            }
        };
    }
}
