package com.minos.hosted;

import java.time.Instant;

/** Portable authentication boundary; callers never supply trusted principal fields directly. */
public interface HostedIdentityVerifier {
    HostedAccessClaims authenticate(String bearerToken, Instant now);
}
