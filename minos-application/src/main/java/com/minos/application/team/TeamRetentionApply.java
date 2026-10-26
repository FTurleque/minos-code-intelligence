package com.minos.application.team;

import java.util.Objects;

/** Result of an applied retention: the plan that was applied and what the tenant keeps afterwards. */
public record TeamRetentionApply(TeamRetentionPlan plan, long tenantVersion, int remainingAuditEvents,
                                 int remainingWorkspaces) {
    public TeamRetentionApply {
        Objects.requireNonNull(plan, "plan");
    }
}
