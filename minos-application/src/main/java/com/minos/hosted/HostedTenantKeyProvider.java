package com.minos.hosted;

import javax.crypto.SecretKey;
import java.util.UUID;

/**
 * Tenant-scoped cryptographic-key resolution port for hosted mode.
 *
 * <p>The embedded baseline may derive or load keys locally. An operated deployment must provide
 * an adapter backed by an independently qualified key-management system and must keep token,
 * audit-chain and encryption purposes cryptographically separated.</p>
 */
@FunctionalInterface
public interface HostedTenantKeyProvider {

    /**
     * Resolves the exact tenant key for the requested identifier and cryptographic purpose.
     *
     * @throws IllegalStateException when the requested key cannot be resolved safely
     */
    SecretKey resolve(UUID tenantId, String keyId, Purpose purpose);

    /** Cryptographic domains that must not share derived key material. */
    enum Purpose {
        TOKEN_SIGNING,
        AUDIT_CHAIN,
        ENCRYPTION
    }
}
