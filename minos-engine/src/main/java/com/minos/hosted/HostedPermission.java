package com.minos.hosted;

/** Explicit hosted-mode authorization capabilities. */
public enum HostedPermission {
    TENANT_ADMIN,
    KEY_ROTATE,
    MEMBER_READ,
    MEMBER_WRITE,
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    BINDING_WRITE,
    AUDIT_READ,
    RETENTION_MANAGE
}
