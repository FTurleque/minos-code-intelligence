package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Persists one authorized tenant mutation, appends the authenticated audit event, then exports it. */
final class HostedTenantMutationWriter {

    private final HostedControlPlaneStore store;
    private final HostedAuditChain auditChain;
    private final HostedAuditSink auditSink;
    private final Clock clock;

    HostedTenantMutationWriter(
            HostedControlPlaneStore store,
            HostedAuditChain auditChain,
            HostedAuditSink auditSink,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.auditChain = Objects.requireNonNull(auditChain, "auditChain");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    HostedTenantState saveAllowed(
            HostedAuthorizationService.MutationContext context,
            List<HostedPrincipal> members,
            List<SharedWorkspace> workspaces,
            HostedRetentionPolicy retention,
            String keyId,
            String auditAnchor,
            List<HostedAuditEvent> audit,
            long auditSequence,
            String action,
            String resourceType,
            String resourceId
    ) throws IOException {
        HostedTenantState business = new HostedTenantState(
                context.state().tenantId(),
                context.state().name(),
                keyId,
                context.state().version(),
                context.state().createdAt(),
                clock.instant(),
                retention,
                members,
                workspaces,
                auditSequence,
                auditAnchor,
                audit);
        HostedTenantState updated = auditChain.append(
                business,
                context.claims().principalId(),
                action,
                resourceType,
                resourceId,
                HostedAuditEvent.Outcome.ALLOWED,
                context.requestId(),
                keyId,
                context.state().version() + 1);
        store.save(updated, context.state().version());
        HostedAuditDelivery.publishAfterCommit(auditSink, updated.auditEvents().getLast());
        return updated;
    }
}
