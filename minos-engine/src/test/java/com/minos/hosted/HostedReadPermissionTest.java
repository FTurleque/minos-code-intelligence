package com.minos.hosted;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-H07 : la voie de lecture exige la permission du rôle. Sans ces tests, retirer la garde
 * {@code role().allows(permission)} de {@code HostedAuthorizationService.authorizeRead} ne faisait échouer aucun test :
 * un VIEWER aurait lu la piste d'audit.
 */
class HostedReadPermissionTest {

    @Test
    void aViewerCannotReadTheAuditTrail() throws Exception {
        HostedControlPlaneTestSupport.Harness harness = HostedControlPlaneTestSupport.harness();
        HostedControlPlaneService service = harness.service();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        long chainedBefore = harness.state().auditSequence();

        SecurityException failure = assertThrows(SecurityException.class, () -> service.audit(viewer, 10));
        assertEquals("hosted permission denied: AUDIT_READ", failure.getMessage());
        assertEquals(chainedBefore, harness.state().auditSequence(), "a read refusal is not chained (ADR 0035)");
    }

    @Test
    void aContributorCannotReadTheAuditTrailNorTheRetentionPlan() throws Exception {
        HostedControlPlaneTestSupport.Harness harness = HostedControlPlaneTestSupport.harness();
        HostedControlPlaneService service = harness.service();
        String owner = harness.bootstrapOwner();
        String contributor = harness.grantAndIssue(owner, "contributor", HostedRole.CONTRIBUTOR);

        assertEquals("hosted permission denied: AUDIT_READ",
                assertThrows(SecurityException.class, () -> service.audit(contributor, 10)).getMessage());
        assertEquals("hosted permission denied: RETENTION_MANAGE",
                assertThrows(SecurityException.class, () -> service.retentionPlan(contributor)).getMessage());
    }

    @Test
    void anAuditorReadsTheAuditTrailButNotTheRetentionPlan() throws Exception {
        HostedControlPlaneTestSupport.Harness harness = HostedControlPlaneTestSupport.harness();
        HostedControlPlaneService service = harness.service();
        String owner = harness.bootstrapOwner();
        String auditor = harness.grantAndIssue(owner, "auditor", HostedRole.AUDITOR);

        assertDoesNotThrow(() -> service.audit(auditor, 10));
        assertThrows(SecurityException.class, () -> service.retentionPlan(auditor));
    }
}
