package com.minos.hosted;

/** Explicit, operator-applied hosted retention policy. No implicit eviction is permitted. */
public record HostedRetentionPolicy(
        int maxAuditEvents,
        int auditRetentionDays,
        int archivedWorkspaceRetentionDays
) {
    public static final int MIN_AUDIT_EVENTS = 100;
    public static final int MAX_AUDIT_EVENTS = 100_000;

    public HostedRetentionPolicy {
        if (maxAuditEvents < MIN_AUDIT_EVENTS || maxAuditEvents > MAX_AUDIT_EVENTS) {
            throw new IllegalArgumentException("maxAuditEvents must be between 100 and 100000");
        }
        if (auditRetentionDays < 1 || auditRetentionDays > 3_650) {
            throw new IllegalArgumentException("auditRetentionDays must be between 1 and 3650");
        }
        if (archivedWorkspaceRetentionDays < 1 || archivedWorkspaceRetentionDays > 3_650) {
            throw new IllegalArgumentException("archivedWorkspaceRetentionDays must be between 1 and 3650");
        }
    }

    public static HostedRetentionPolicy defaults() {
        return new HostedRetentionPolicy(10_000, 365, 90);
    }
}
