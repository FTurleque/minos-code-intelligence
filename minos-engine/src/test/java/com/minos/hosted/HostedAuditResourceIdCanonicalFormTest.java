package com.minos.hosted;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-B01 : un refus RBAC dont l'identifiant de ressource n'est pas canonique (espaces ou caractères de
 * contrôle en tête ou en fin) laisse la chaîne d'audit vérifiable, que le refus passe par l'attribution d'un rôle,
 * une révocation ou l'émission d'un jeton.
 */
class HostedAuditResourceIdCanonicalFormTest {

    @ParameterizedTest
    @ValueSource(strings = {" ghost", "\tghost", "ghost ", "  ghost  "})
    void aRefusedGrantWithANonCanonicalIdKeepsTheChainVerifiable(String raw) throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        assertThrows(SecurityException.class,
                () -> harness.service().grantMember(viewer, "r1", raw, "G", HostedRole.VIEWER));

        assertChainStaysUsable(harness, owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ghost", "\tghost", "ghost ", "  ghost  "})
    void aRefusedRevocationWithANonCanonicalIdKeepsTheChainVerifiable(String raw) throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        assertThrows(SecurityException.class, () -> harness.service().revokeMember(viewer, "r2", raw));

        assertChainStaysUsable(harness, owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ghost", "\tghost", "ghost ", "  ghost  "})
    void aRefusedTokenIssueWithANonCanonicalIdKeepsTheChainVerifiable(String raw) throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        assertThrows(SecurityException.class,
                () -> harness.service().issueToken(viewer, "r3", raw, Duration.ofMinutes(5)));

        assertChainStaysUsable(harness, owner);
    }

    @Test
    void theRefusalIsAuditedUnderTheCanonicalValueThatTheChainAuthenticates() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

        assertThrows(SecurityException.class,
                () -> harness.service().grantMember(viewer, "r1", " ghost", "G", HostedRole.VIEWER));

        HostedAuditEvent denial = harness.state().auditEvents().getLast();
        assertEquals(HostedAuditEvent.Outcome.DENIED, denial.outcome());
        assertEquals("ghost", denial.resourceId());
    }

    private static void assertChainStaysUsable(HostedControlPlaneTestSupport.Harness harness, String owner) {
        assertDoesNotThrow(() -> harness.service().tenant(owner), "the next read must not fail the audit authentication");
        assertDoesNotThrow(() -> new HostedAuditChain(harness.keys(), Clock.systemUTC()).verify(harness.state()));
        assertDoesNotThrow(() -> harness.service().createWorkspace(owner, "after-refusal", "Shared"),
                "the owner must still be able to mutate the tenant");
    }
}
