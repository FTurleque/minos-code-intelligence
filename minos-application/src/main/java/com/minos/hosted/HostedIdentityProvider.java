package com.minos.hosted;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Identity-provider port for hosted mode.
 *
 * <p>The embedded baseline uses {@link HmacHostedIdentityProvider}. An operated deployment must
 * supply an adapter whose token issuance, verification and lifecycle are qualified independently.</p>
 */
public interface HostedIdentityProvider extends HostedIdentityVerifier {

    String issue(
            UUID tenantId,
            String principalId,
            String keyId,
            Instant issuedAt,
            Duration lifetime,
            String tokenId
    );
}
