package com.minos.store;

import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHostedControlPlaneStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void persistsOnlyCiphertextAndRoundTripsTenantState(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, keys());
        HostedTenantState state = state(tenant, "Secret Team", "primary", 0);
        store.create(state);

        assertEquals(state, store.find(tenant).orElseThrow());
        byte[] persisted = Files.readAllBytes(root.resolve(tenant + ".mht"));
        String raw = new String(persisted, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("Secret Team"));
        assertFalse(raw.contains("Owner Display"));
    }

    @Test
    void rejectsTamperingBeforePlaintextDeserialization(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, keys());
        store.create(state(tenant, "Tenant", "primary", 0));
        Path file = root.resolve(tenant + ".mht");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        IOException failure = assertThrows(IOException.class, () -> store.find(tenant));
        assertTrue(failure.getMessage().contains("authentication tag"));
    }

    @Test
    void enforcesOptimisticConcurrencyAndSupportsExplicitKeyRotation(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, keys());
        HostedTenantState initial = state(tenant, "Tenant", "primary", 0);
        store.create(initial);
        HostedTenantState first = state(tenant, "First", "primary", 1);
        HostedTenantState conflicting = state(tenant, "Conflicting", "primary", 1);
        store.save(first, 0);
        IOException conflict = assertThrows(IOException.class, () -> store.save(conflicting, 0));
        assertTrue(conflict.getMessage().contains("concurrent modification"));

        HostedTenantState rotated = state(tenant, "First", "rotated", 2);
        store.save(rotated, 1);
        assertEquals("rotated", store.find(tenant).orElseThrow().keyId());
    }

    @Test
    void rejectsTenantFileSymlink(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        Path outside = Files.writeString(root.resolve("outside"), "outside");
        Path link = root.resolve("store").resolve(tenant + ".mht");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root.resolve("store"), keys());
        IOException failure = assertThrows(IOException.class,
                () -> store.create(state(tenant, "Tenant", "primary", 0)));
        assertTrue(failure.getMessage().contains("symbolic link"));
    }

    @Test
    void environmentProviderDerivesTenantAndPurposeSeparatedKeys() {
        String master = Base64.getEncoder().encodeToString(new byte[32]);
        EnvironmentHostedTenantKeyProvider provider = new EnvironmentHostedTenantKeyProvider(
                name -> "MINOS_TEAM_KEY_PRIMARY".equals(name) ? master : null);
        UUID firstTenant = UUID.randomUUID();
        SecretKey encryption = provider.resolve(firstTenant, "primary", HostedTenantKeyProvider.Purpose.ENCRYPTION);
        SecretKey audit = provider.resolve(firstTenant, "primary", HostedTenantKeyProvider.Purpose.AUDIT_CHAIN);
        SecretKey otherTenant = provider.resolve(UUID.randomUUID(), "primary", HostedTenantKeyProvider.Purpose.ENCRYPTION);

        assertEquals(32, encryption.getEncoded().length);
        assertNotEquals(Base64.getEncoder().encodeToString(encryption.getEncoded()),
                Base64.getEncoder().encodeToString(audit.getEncoded()));
        assertNotEquals(Base64.getEncoder().encodeToString(encryption.getEncoded()),
                Base64.getEncoder().encodeToString(otherTenant.getEncoded()));
        assertThrows(IllegalStateException.class,
                () -> provider.resolve(firstTenant, "missing", HostedTenantKeyProvider.Purpose.ENCRYPTION));
    }

    private static HostedTenantState state(UUID tenant, String name, String keyId, long version) {
        HostedPrincipal owner = new HostedPrincipal("owner", "Owner Display", HostedRole.OWNER, NOW);
        return new HostedTenantState(tenant, name, keyId, version, NOW, NOW,
                HostedRetentionPolicy.defaults(), List.of(owner), List.of(), 0,
                HostedAuditEvent.GENESIS_HASH, List.of());
    }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            try {
                byte[] value = MessageDigest.getInstance("SHA-256")
                        .digest((tenantId + ":" + keyId + ":" + purpose).getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(value, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION
                        ? "AES" : "HmacSHA256");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
    }
}
