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

    /**
     * Whether a principal holding this role may assign, alter, revoke or act on behalf of
     * {@code other}. Roles are not totally ordered, so governance is defined on the permission
     * sets: a role governs exactly the roles whose permissions are a subset of its own. Every role
     * governs itself; only {@link #OWNER} governs {@link #OWNER}.
     */
    public boolean canGovern(HostedRole other) {
        return permissions.containsAll(other.permissions());
    }
}
