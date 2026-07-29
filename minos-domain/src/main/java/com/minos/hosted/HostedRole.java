package com.minos.hosted;

import java.util.EnumSet;
import java.util.Set;

/** Closed M27 role set; permissions are never inferred from role names. */
public enum HostedRole {
    OWNER(EnumSet.allOf(HostedPermission.class)),
    ADMIN(EnumSet.complementOf(EnumSet.of(HostedPermission.KEY_ROTATE))),
    CONTRIBUTOR(EnumSet.of(
            HostedPermission.MEMBER_READ,
            HostedPermission.WORKSPACE_READ,
            HostedPermission.WORKSPACE_WRITE,
            HostedPermission.BINDING_WRITE)),
    VIEWER(EnumSet.of(HostedPermission.MEMBER_READ, HostedPermission.WORKSPACE_READ)),
    AUDITOR(EnumSet.of(
            HostedPermission.MEMBER_READ,
            HostedPermission.WORKSPACE_READ,
            HostedPermission.AUDIT_READ));

    private final Set<HostedPermission> permissions;

    HostedRole(Set<HostedPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean allows(HostedPermission permission) {
        return permissions.contains(permission);
    }

    public Set<HostedPermission> permissions() {
        return permissions;
    }
}
