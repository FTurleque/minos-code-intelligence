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
            HostedTenantState denied = auditChain.append(
                    state,
                    claims.principalId(),
                    action,
                    resourceType,
                    resourceId,
                    HostedAuditEvent.Outcome.DENIED,
                    safeRequestId,
                    state.keyId(),
                    state.version() + 1);
            store.save(denied, state.version());
            auditSink.publish(denied.auditEvents().getLast());
            throw new SecurityException("hosted permission denied: " + permission);
        }
        return new MutationContext(claims, state, membership.orElseThrow(), safeRequestId);
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
