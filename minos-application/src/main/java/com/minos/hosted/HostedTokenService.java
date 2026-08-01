package com.minos.hosted;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Short-lived token issuance and tenant key rotation operations. */
final class HostedTokenService {

    private final HostedAuthorizationService authorization;
    private final HostedIdentityProvider identities;
    private final HostedTenantKeyProvider keys;
    private final HostedTenantMutationWriter writer;
    private final Clock clock;

    HostedTokenService(
            HostedAuthorizationService authorization,
            HostedIdentityProvider identities,
            HostedTenantKeyProvider keys,
            HostedTenantMutationWriter writer,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    String issue(
            String bearerToken,
            String requestId,
            String targetPrincipalId,
            Duration lifetime
    ) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.TENANT_ADMIN,
                "TOKEN_ISSUE", "PRINCIPAL", targetPrincipalId);
        String target = HostedPrincipal.safeId(targetPrincipalId, "targetPrincipalId");
        if (context.state().members().stream().noneMatch(member -> member.principalId().equals(target))) {
            throw new IllegalArgumentException("token target is not a tenant member");
        }
        HostedTenantState saved = writer.saveAllowed(
                context,
                context.state().members(),
                context.state().workspaces(),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "TOKEN_ISSUE", "PRINCIPAL", target);
        return identities.issue(
                saved.tenantId(), target, saved.keyId(), clock.instant(),
                Objects.requireNonNull(lifetime, "lifetime"), UUID.randomUUID().toString());
    }

    Rotation rotate(
            String bearerToken,
            String requestId,
            String newKeyId,
            Duration replacementTokenLifetime
    ) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.KEY_ROTATE,
                "KEY_ROTATE", "TENANT", "key");
        String safeNewKeyId = HostedPrincipal.safeId(newKeyId, "newKeyId");
        if (context.state().keyId().equals(safeNewKeyId)) {
            throw new IllegalArgumentException("new keyId matches current keyId");
        }
        requireKeys(context.state().tenantId(), safeNewKeyId);
        HostedTenantState saved = writer.saveAllowed(
                context,
                context.state().members(),
                context.state().workspaces(),
                context.state().retentionPolicy(),
                safeNewKeyId,
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "KEY_ROTATE", "TENANT", "key");
        String replacement = identities.issue(
                saved.tenantId(),
                context.claims().principalId(),
                safeNewKeyId,
                clock.instant(),
                Objects.requireNonNull(replacementTokenLifetime, "replacementTokenLifetime"),
                UUID.randomUUID().toString());
        return new Rotation(saved, replacement);
    }

    void requireKeys(UUID tenantId, String keyId) {
        for (HostedTenantKeyProvider.Purpose purpose : HostedTenantKeyProvider.Purpose.values()) {
            SecretKey key = keys.resolve(tenantId, keyId, purpose);
            if (key.getEncoded() == null || key.getEncoded().length != 32) {
                throw new IllegalStateException("hosted derived key must contain 256 bits");
            }
        }
    }

    record Rotation(HostedTenantState state, String replacementBearerToken) {
        Rotation {
            Objects.requireNonNull(state, "state");
            replacementBearerToken = HostedPrincipal.text(
                    replacementBearerToken, "replacementBearerToken", 8192);
        }
    }
}
