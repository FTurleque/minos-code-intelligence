package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Retention policy, deterministic plan and explicit destructive application. */
final class HostedRetentionService {

    private final HostedAuthorizationService authorization;
    private final HostedTenantMutationWriter writer;
    private final Clock clock;

    HostedRetentionService(
            HostedAuthorizationService authorization,
            HostedTenantMutationWriter writer,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    HostedRetentionPolicy set(
            String bearerToken,
            String requestId,
            HostedRetentionPolicy policy
    ) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.RETENTION_MANAGE,
                "RETENTION_SET", "TENANT", "policy");
        HostedRetentionPolicy safePolicy = Objects.requireNonNull(policy, "policy");
        writer.saveAllowed(
                context,
                context.state().members(),
                context.state().workspaces(),
                safePolicy,
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "RETENTION_SET", "TENANT", "policy");
        return safePolicy;
    }

    HostedRetentionPlan plan(String bearerToken) throws IOException {
        HostedTenantState state = authorization.authorizeRead(
                bearerToken, HostedPermission.RETENTION_MANAGE).state();
        return plan(state, clock.instant());
    }

    ApplyResult apply(String bearerToken, String requestId) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.RETENTION_MANAGE,
                "RETENTION_APPLY", "TENANT", "retention");
        HostedRetentionPlan plan = plan(context.state(), clock.instant());
        List<HostedAuditEvent> audit = context.state().auditEvents();
        String anchor = context.state().auditAnchorHash();
        if (plan.auditEventsToRemove() > 0) {
            anchor = audit.get(plan.auditEventsToRemove() - 1).hash();
            audit = List.copyOf(audit.subList(plan.auditEventsToRemove(), audit.size()));
        }
        Set<UUID> removed = Set.copyOf(plan.archivedWorkspacesToRemove());
        List<SharedWorkspace> workspaces = context.state().workspaces().stream()
                .filter(value -> !removed.contains(value.workspaceId()))
                .toList();
        HostedTenantState saved = writer.saveAllowed(
                context,
                context.state().members(),
                workspaces,
                context.state().retentionPolicy(),
                context.state().keyId(),
                anchor,
                audit,
                context.state().auditSequence(),
                "RETENTION_APPLY", "TENANT", "retention");
        return new ApplyResult(plan, saved);
    }

    private static HostedRetentionPlan plan(HostedTenantState state, Instant now) {
        Instant auditCutoff = now.minus(state.retentionPolicy().auditRetentionDays(), ChronoUnit.DAYS);
        int byAge = 0;
        while (byAge < state.auditEvents().size()
                && state.auditEvents().get(byAge).occurredAt().isBefore(auditCutoff)) {
            byAge++;
        }
        int byCount = Math.max(0, state.auditEvents().size() - state.retentionPolicy().maxAuditEvents());
        int auditToRemove = Math.max(byAge, byCount);
        Instant workspaceCutoff = now.minus(
                state.retentionPolicy().archivedWorkspaceRetentionDays(), ChronoUnit.DAYS);
        List<UUID> workspaces = state.workspaces().stream()
                .filter(value -> value.status() == SharedWorkspace.Status.ARCHIVED)
                .filter(value -> value.archivedAt().isBefore(workspaceCutoff))
                .map(SharedWorkspace::workspaceId)
                .sorted()
                .toList();
        return new HostedRetentionPlan(state.tenantId(), now, auditToRemove, workspaces);
    }

    record ApplyResult(HostedRetentionPlan plan, HostedTenantState state) {
        ApplyResult {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(state, "state");
        }
    }
}
