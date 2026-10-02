package com.minos.hosted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authenticated bearer-token claims; membership and authorization are checked separately. */
public record HostedAccessClaims(
        UUID tenantId,
        String principalId,
        String keyId,
        Instant issuedAt,
        Instant expiresAt,
        String tokenId
) {
    public HostedAccessClaims {
        Objects.requireNonNull(tenantId, "tenantId");
        principalId = HostedPrincipal.safeId(principalId, "principalId");
        keyId = HostedPrincipal.safeId(keyId, "keyId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("token expiry must follow issuance");
        tokenId = HostedPrincipal.safeId(tokenId, "tokenId");
    }
}
