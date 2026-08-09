package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant bootstrap, authenticated tenant reads and bounded audit reads. */
final class HostedTenantService {

    private final HostedControlPlaneStore store;
    private final HostedIdentityProvider identities;
    private final HostedAuthorizationService authorization;
    private final HostedAuditChain auditChain;
    private final HostedAuditSink auditSink;
    private final HostedTokenService tokens;
    private final Clock clock;

    HostedTenantService(
            HostedControlPlaneStore store,
            HostedIdentityProvider identities,
            HostedAuthorizationService authorization,
            HostedAuditChain auditChain,
            HostedAuditSink auditSink,
            HostedTokenService tokens,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.auditChain = Objects.requireNonNull(auditChain, "auditChain");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Bootstrap bootstrap(
            UUID tenantId,
            String tenantName,
            String keyId,
            String ownerPrincipalId,
            String ownerDisplayName,
            Duration tokenLifetime,
            String requestId
    ) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        String safeKeyId = HostedPrincipal.safeId(keyId, "keyId");
        String ownerId = HostedPrincipal.safeId(ownerPrincipalId, "ownerPrincipalId");
        String safeRequestId = HostedPrincipal.safeId(requestId, "requestId");
        tokens.requireKeys(tenantId, safeKeyId);
        var now = clock.instant();
        HostedPrincipal owner = new HostedPrincipal(ownerId, ownerDisplayName, HostedRole.OWNER, now);
        HostedTenantState initial = new HostedTenantState(
                tenantId,
                tenantName,
                safeKeyId,
                0,
                now,
                now,
                HostedRetentionPolicy.defaults(),
                List.of(owner),
                List.of(),
                0,
                HostedAuditEvent.GENESIS_HASH,
                List.of());
        HostedTenantState audited = auditChain.append(
                initial,
                ownerId,
                "TENANT_BOOTSTRAP",
                "TENANT",
                tenantId.toString(),
                HostedAuditEvent.Outcome.ALLOWED,
                safeRequestId,
                safeKeyId,
                0);

        Duration safeLifetime = Objects.requireNonNull(tokenLifetime, "tokenLifetime");
        String token = identities.issue(
                tenantId, ownerId, safeKeyId, now,
                safeLifetime, UUID.randomUUID().toString());

        store.create(audited);
        HostedAuditDelivery.publishAfterCommit(auditSink, audited.auditEvents().getLast());
        return new Bootstrap(audited, token);
    }

    HostedTenantState tenant(String bearerToken) throws IOException {
        return authorization.authorizeRead(bearerToken, HostedPermission.WORKSPACE_READ).state();
    }

    List<HostedAuditEvent> audit(String bearerToken, int limit) throws IOException {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("audit limit must be between 1 and 10000");
        }
        HostedTenantState state = authorization.authorizeRead(
                bearerToken, HostedPermission.AUDIT_READ).state();
        int start = Math.max(0, state.auditEvents().size() - limit);
        List<HostedAuditEvent> values = new ArrayList<>(
                state.auditEvents().subList(start, state.auditEvents().size()));
        java.util.Collections.reverse(values);
        return List.copyOf(values);
    }

    record Bootstrap(HostedTenantState state, String bearerToken) {
        Bootstrap {
            Objects.requireNonNull(state, "state");
            bearerToken = HostedPrincipal.text(bearerToken, "bearerToken", 8192);
        }
    }
}
