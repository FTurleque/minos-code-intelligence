package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S1: nobody may grant, demote, revoke or impersonate a role they do not govern. */
class HostedRoleGovernanceTest {

    @Test
    void adminCannotPromoteItselfToOwner() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);

        SecurityException denied = assertThrows(SecurityException.class,
                () -> harness.service().grantMember(admin, "escalate-self", "admin", "Admin", HostedRole.OWNER));

        assertFalse(denied.getMessage().contains("OWNER"));
        assertEquals(HostedRole.ADMIN, roleOf(harness, "admin"));
    }

    @Test
    void adminCannotPromoteAnotherMemberToOwner() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);

        assertThrows(SecurityException.class,
                () -> harness.service().grantMember(admin, "escalate-other", "friend", "Friend", HostedRole.OWNER));

        assertTrue(harness.state().members().stream().noneMatch(member -> member.principalId().equals("friend")));
    }

    @Test
    void adminMayGrantAdminAndOwnerMayGrantOwner() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);

        harness.service().grantMember(admin, "grant-peer", "peer", "Peer", HostedRole.ADMIN);
        harness.service().grantMember(owner, "grant-owner", "second-owner", "Second", HostedRole.OWNER);

        assertEquals(HostedRole.ADMIN, roleOf(harness, "peer"));
        assertEquals(HostedRole.OWNER, roleOf(harness, "second-owner"));
    }

    @Test
    void adminCannotDemoteOrRevokeAnOwner() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        harness.service().grantMember(owner, "grant-owner", "second-owner", "Second", HostedRole.OWNER);
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);

        assertThrows(SecurityException.class,
                () -> harness.service().grantMember(admin, "demote", "second-owner", "Second", HostedRole.VIEWER));
        assertThrows(SecurityException.class,
                () -> harness.service().revokeMember(admin, "revoke", "second-owner"));

        assertEquals(HostedRole.OWNER, roleOf(harness, "second-owner"));
    }

    @Test
    void lastOwnerCannotBeDemotedOrRevokedEvenByItself() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();

        assertThrows(IllegalArgumentException.class,
                () -> harness.service().grantMember(owner, "demote-self", "owner", "Owner", HostedRole.ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().revokeMember(owner, "revoke-self", "owner"));

        assertEquals(HostedRole.OWNER, roleOf(harness, "owner"));
    }

    @Test
    void adminCannotIssueTokenOnBehalfOfOwner() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);

        assertThrows(SecurityException.class,
                () -> harness.service().issueToken(admin, "impersonate", "owner", Duration.ofHours(1)));
    }

    @Test
    void ownerMayIssueTokenOnBehalfOfAdmin() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        harness.service().grantMember(owner, "grant-admin", "admin", "Admin", HostedRole.ADMIN);

        String admin = harness.service().issueToken(owner, "delegate", "admin", Duration.ofHours(1));

        assertEquals(harness.tenant(), harness.service().tenant(admin).tenantId());
    }

    @Test
    void governanceRefusalIsAuditedAsDeniedAndPublished() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String admin = harness.grantAndIssue(owner, "admin", HostedRole.ADMIN);

        assertThrows(SecurityException.class,
                () -> harness.service().grantMember(admin, "escalate-audited", "admin", "Admin", HostedRole.OWNER));

        List<HostedAuditEvent> audit = harness.service().audit(owner, 20);
        HostedAuditEvent denied = audit.stream()
                .filter(event -> event.requestId().equals("escalate-audited"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no audit event for the refused grant"));
        assertEquals(HostedAuditEvent.Outcome.DENIED, denied.outcome());
        assertEquals("MEMBER_GRANT", denied.action());
        assertEquals("admin", denied.principalId());
        assertTrue(harness.sink().events.stream().anyMatch(event -> event.equals(denied)));
    }

    private static HostedRole roleOf(HostedControlPlaneTestSupport.Harness harness, String principalId) {
        return harness.state().members().stream()
                .filter(member -> member.principalId().equals(principalId))
                .map(HostedPrincipal::role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("member missing: " + principalId));
    }
}
