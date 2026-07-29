package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedModelTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void roleMatrixIsExplicitAndLeastPrivilege() {
        assertTrue(HostedRole.OWNER.allows(HostedPermission.KEY_ROTATE));
        assertFalse(HostedRole.ADMIN.allows(HostedPermission.KEY_ROTATE));
        assertTrue(HostedRole.CONTRIBUTOR.allows(HostedPermission.BINDING_WRITE));
        assertFalse(HostedRole.CONTRIBUTOR.allows(HostedPermission.MEMBER_WRITE));
        assertTrue(HostedRole.AUDITOR.allows(HostedPermission.AUDIT_READ));
        assertFalse(HostedRole.VIEWER.allows(HostedPermission.AUDIT_READ));
    }

    @Test
    void tenantRejectsCrossTenantWorkspaceDuplicateMembersAndMissingOwner() {
        UUID tenant = UUID.randomUUID();
        HostedPrincipal owner = new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW);
        SharedWorkspace crossTenant = new SharedWorkspace(UUID.randomUUID(), UUID.randomUUID(), "shared",
                SharedWorkspace.Status.ACTIVE, NOW, NOW, null, List.of());

        assertThrows(IllegalArgumentException.class, () -> state(tenant, List.of(owner), List.of(crossTenant)));
        assertThrows(IllegalArgumentException.class, () -> state(tenant, List.of(owner, owner), List.of()));
        assertThrows(IllegalArgumentException.class, () -> state(tenant,
                List.of(new HostedPrincipal("viewer", "Viewer", HostedRole.VIEWER, NOW)), List.of()));
    }

    @Test
    void sharedWorkspaceRejectsDuplicateProjectBindingsAndInconsistentArchiveState() {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        HostedProjectBinding first = new HostedProjectBinding(project, "snapshot-1", NOW, "owner");
        HostedProjectBinding second = new HostedProjectBinding(project, "snapshot-2", NOW, "owner");
        assertThrows(IllegalArgumentException.class, () -> new SharedWorkspace(
                UUID.randomUUID(), tenant, "shared", SharedWorkspace.Status.ACTIVE, NOW, NOW, null,
                List.of(first, second)));
        assertThrows(IllegalArgumentException.class, () -> new SharedWorkspace(
                UUID.randomUUID(), tenant, "shared", SharedWorkspace.Status.ARCHIVED, NOW, NOW, null, List.of()));
    }

    private static HostedTenantState state(
            UUID tenant,
            List<HostedPrincipal> members,
            List<SharedWorkspace> workspaces
    ) {
        return new HostedTenantState(tenant, "Tenant", "primary", 0, NOW, NOW,
                HostedRetentionPolicy.defaults(), members, workspaces, 0,
                HostedAuditEvent.GENESIS_HASH, List.of());
    }
}
