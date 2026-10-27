package com.minos.storage.local.store;

import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantState;
import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MINOS-AUD-H07 : chaque écriture de l'état chiffré d'un tenant utilise un nonce AES-GCM neuf. Avant ce test,
 * supprimer le tirage aléatoire du nonce ({@code FileHostedControlPlaneStore.writeAtomically}) ne faisait échouer aucun
 * test ; le nonce serait resté nul, et réutiliser un nonce avec la même clé détruit la confidentialité et
 * l'authenticité de GCM.
 */
class HostedTenantNonceTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
    private static final int NONCE_BYTES = 12;

    @Test
    void everyWriteOfTheSameTenantUsesAFreshNonZeroNonce(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, DerivedTenantKeys.provider());
        Path file = root.resolve(tenant + ".mht");

        store.create(state(tenant, 0));
        byte[] first = nonce(Files.readAllBytes(file));
        store.save(state(tenant, 1), 0);
        byte[] second = nonce(Files.readAllBytes(file));

        assertEquals(NONCE_BYTES, first.length);
        assertFalse(Arrays.equals(new byte[NONCE_BYTES], first), "the nonce must not be all zero");
        assertFalse(Arrays.equals(first, second), "two writes of the same tenant must not reuse a nonce");
    }

    /** Envelope header: magic, version, tenant id (two longs), key id (length + UTF-8), nonce length, nonce. */
    private static byte[] nonce(byte[] envelope) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
            input.readInt();
            input.readInt();
            input.readLong();
            input.readLong();
            input.skipNBytes(input.readInt());
            return input.readNBytes(input.readInt());
        }
    }

    private static HostedTenantState state(UUID tenant, long version) {
        HostedPrincipal owner = new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW);
        return new HostedTenantState(tenant, "Team", "primary", version, NOW, NOW,
                HostedRetentionPolicy.defaults(), List.of(owner), List.of(), 0,
                HostedAuditEvent.GENESIS_HASH, List.of());
    }
}
