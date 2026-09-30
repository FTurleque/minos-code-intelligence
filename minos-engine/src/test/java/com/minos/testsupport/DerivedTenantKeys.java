package com.minos.testsupport;

import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.io.Sha256;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Fournisseur de clés de test : la clé d'un (locataire, identifiant, usage) est le SHA-256 de leur concaténation,
 * stable d'un appel à l'autre et sans matériau secret réel. Un seul exemplaire pour les tests hébergés de
 * {@code minos-engine} et de {@code minos-storage-local} (Q13, jusque-là cinq copies).
 */
public final class DerivedTenantKeys {

    private DerivedTenantKeys() {
    }

    public static HostedTenantKeyProvider provider() {
        return (tenantId, keyId, purpose) -> new SecretKeySpec(
                Sha256.newDigest().digest((tenantId + ":" + keyId + ":" + purpose).getBytes(StandardCharsets.UTF_8)),
                purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
    }
}
