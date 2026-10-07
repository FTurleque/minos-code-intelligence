package com.minos.hosted;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B03 : l'autorisation précède la validation. Un appelant dont les droits sont suffisants et qui fournit
 * un identifiant invalide reçoit une requête invalide sans événement de refus ; le même identifiant fourni par un
 * appelant sans droit est un refus d'autorisation tracé.
 */
class HostedAuthorizedInvalidIdentifierTest {

    @Test
    void anOwnerWithAnInvalidIdentifierIsRejectedAsAnInvalidRequestWithoutARefusalEvent() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        HostedTenantState before = harness.state();

        assertThrows(IllegalArgumentException.class,
                () -> harness.service().revokeMember(owner, "r1", "a".repeat(5000)));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().grantMember(owner, "r2", "bad id", "Bad", HostedRole.VIEWER));

        assertEquals(before, harness.state(), "no event, no version change");
        assertTrue(harness.sink().unchained.isEmpty());
    }

    @Test
    void aCallerWithoutRightsPresentingTheSameInvalidIdentifierIsRefusedAndTraced() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        int before = harness.state().auditEvents().size();

        assertThrows(SecurityException.class,
                () -> harness.service().revokeMember(viewer, "r1", "a".repeat(5000)));
        assertThrows(SecurityException.class,
                () -> harness.service().grantMember(viewer, "r2", "bad id", "Bad", HostedRole.VIEWER));

        assertEquals(before + 2, harness.state().auditEvents().size());
        harness.state().auditEvents().subList(before, before + 2)
                .forEach(event -> assertEquals(HostedAuditEvent.Outcome.DENIED, event.outcome()));
    }
}
