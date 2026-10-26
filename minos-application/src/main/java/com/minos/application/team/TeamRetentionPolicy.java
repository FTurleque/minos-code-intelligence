package com.minos.application.team;

import com.minos.hosted.HostedRetentionPolicy;

/**
 * Explicit retention policy of a tenant. Its bounds and its validation are those of the hosted control plane: an
 * invalid policy is refused with the message of the engine.
 */
public record TeamRetentionPolicy(int maxAuditEvents, int auditRetentionDays, int archivedWorkspaceRetentionDays) {
    public static final int MIN_AUDIT_EVENTS = HostedRetentionPolicy.MIN_AUDIT_EVENTS;
    public static final int MAX_AUDIT_EVENTS = HostedRetentionPolicy.MAX_AUDIT_EVENTS;
    public static final int MIN_RETENTION_DAYS = HostedRetentionPolicy.MIN_RETENTION_DAYS;
    public static final int MAX_RETENTION_DAYS = HostedRetentionPolicy.MAX_RETENTION_DAYS;

    public TeamRetentionPolicy {
        new HostedRetentionPolicy(maxAuditEvents, auditRetentionDays, archivedWorkspaceRetentionDays);
    }

    static TeamRetentionPolicy of(HostedRetentionPolicy value) {
        return new TeamRetentionPolicy(value.maxAuditEvents(), value.auditRetentionDays(),
                value.archivedWorkspaceRetentionDays());
    }

    HostedRetentionPolicy toHosted() {
        return new HostedRetentionPolicy(maxAuditEvents, auditRetentionDays, archivedWorkspaceRetentionDays);
    }
}
