package com.minos.hosted;

import javax.crypto.SecretKey;
import java.util.UUID;

/** External key-resolution boundary. Key material must never be persisted by MINOS. */
public interface HostedTenantKeyProvider {
    enum Purpose { ENCRYPTION, TOKEN_SIGNING, AUDIT_CHAIN }

    SecretKey resolve(UUID tenantId, String keyId, Purpose purpose);
}
