package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Central fail-closed authentication, membership and RBAC enforcement for hosted operations. */
final class HostedAuthorizationService {

    private final HostedControlPlaneStore store;
    private final HostedIdentityProvider identities;
    private final HostedAuditChain auditChain;
    private final HostedAuditSink auditSink;
    private final Clock clock;

    HostedAuthorizationService(
            HostedControlPlaneStore store,
            HostedIdentityProvider identities,
            HostedAuditChain auditChain,
            HostedAuditSink auditSink,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.auditChain = Objects.requireNonNull(auditChain, "auditChain");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Authorization authorizeRead(String bearerToken, HostedPermission permission) throws IOException {
        HostedAccessClaims claims = identities.authenticate(bearerToken, clock.instant());
        HostedTenantState state = loadVerified(claims.tenantId());
        if (!claims.keyId().equals(state.keyId())) {
            throw new SecurityException("hosted bearer token uses an inactive key");
        }
        HostedPrincipal principal = member(state, claims.principalId())
                .orElseThrow(() -> new SecurityException("authenticated principal is not a tenant member"));
        if (!principal.role().allows(permission)) {
            throw new SecurityException("hosted permission denied: " + permission);
        }
        return new Authorization(claims, state, principal);
    }

    MutationContext authorizeMutation(
            String bearerToken,
            String requestId,
            HostedPermission permission,
            String action,
            String resourceType,
            String resourceId
    ) throws IOException {
        String safeRequestId = HostedPrincipal.safeId(requestId, "requestId");
        HostedAccessClaims claims = identities.authenticate(bearerToken, clock.instant());
        HostedTenantState state = loadVerified(claims.tenantId());
        Optional<HostedPrincipal> membership = member(state, claims.principalId());
        boolean allowed = claims.keyId().equals(state.keyId()) && membership.isPresent()
                && membership.orElseThrow().role().allows(permission);
        if (!allowed) {
            recordDenial(state, claims.principalId(), action, resourceType, resourceId, safeRequestId);
            throw new SecurityException("hosted permission denied: " + permission);
        }
        return new MutationContext(claims, state, membership.orElseThrow(), safeRequestId);
    }

    /**
     * Refuses a mutation whose coarse permission check already passed but whose business rule
     * (for example role governance) forbids it. The refusal is audited, persisted and published
     * exactly like a permission refusal; the returned exception carries only the action name.
     */
    SecurityException deny(
            MutationContext context,
            String action,
            String resourceType,
            String resourceId
    ) throws IOException {
        recordDenial(
                context.state(), context.claims().principalId(), action, resourceType, resourceId,
                context.requestId());
        return new SecurityException("hosted permission denied: " + action);
    }

    private void recordDenial(
            HostedTenantState state,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            String requestId
    ) throws IOException {
        HostedTenantState denied = auditChain.append(
                state,
                principalId,
                action,
                resourceType,
                resourceId,
                HostedAuditEvent.Outcome.DENIED,
                requestId,
                state.keyId(),
                state.version() + 1);
        HostedCommitRecovery.save(store, denied, state.version());
        HostedAuditDelivery.publishAfterCommit(auditSink, denied.auditEvents().getLast());
    }

    HostedTenantState loadVerified(UUID tenantId) throws IOException {
        HostedTenantState state = store.find(tenantId)
                .orElseThrow(() -> new SecurityException("authenticated hosted tenant does not exist"));
        auditChain.verify(state);
        return state;
    }

    private static Optional<HostedPrincipal> member(HostedTenantState state, String principalId) {
        return state.members().stream()
                .filter(value -> value.principalId().equals(principalId))
                .findFirst();
    }

    record Authorization(HostedAccessClaims claims, HostedTenantState state, HostedPrincipal principal) {
        Authorization {
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(principal, "principal");
        }
    }

    record MutationContext(
            HostedAccessClaims claims,
            HostedTenantState state,
            HostedPrincipal principal,
            String requestId
    ) {
        MutationContext {
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(principal, "principal");
            requestId = HostedPrincipal.safeId(requestId, "requestId");
        }
    }
}
