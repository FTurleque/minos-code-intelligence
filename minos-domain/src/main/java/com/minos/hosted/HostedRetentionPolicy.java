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

    /**
     * Maximum number of refusals ({@code DENIED} events) the durable chain may hold: one tenth of
     * the retention target is kept out of reach of refusals. The reserve counts refusals only, so
     * authorized traffic, however large before an explicit retention, never prevents the first
     * refusal of an attack from being chained. Authorized mutations are only bounded by
     * {@link #MAX_AUDIT_EVENTS}.
     */
    public int deniedAuditCapacity() {
        return maxAuditEvents - maxAuditEvents / 10;
    }

    /**
     * Audit slots below {@link #MAX_AUDIT_EVENTS} that refusals never consume, so that however many
     * refusals are chained, authorized mutations (including {@code RETENTION_APPLY}) still find
     * room in the chain.
     */
    public int authorizedAuditHeadroom() {
        return maxAuditEvents / 10;
    }

    /**
     * Whether one more refusal may be appended to a chain that already holds {@code chainedDenials}
     * refusals among {@code chainSize} events: the refusal reserve is not exhausted and the
     * authorized headroom below the hard capacity stays untouched.
     */
    public boolean admitsChainedDenial(long chainedDenials, int chainSize) {
        return chainedDenials < deniedAuditCapacity()
                && chainSize < MAX_AUDIT_EVENTS - authorizedAuditHeadroom();
    }
}
