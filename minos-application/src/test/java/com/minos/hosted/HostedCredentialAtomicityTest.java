package com.minos.hosted;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedCredentialAtomicityTest {

    private static final Instant NOW = Instant.parse("2026-08-09T20:00:00Z");

    @Test
    void bootstrapTokenFailureLeavesNoTenantBehind() {
        Fixture fixture = fixture(HostedAuditSink.embeddedNoop());
        fixture.identities.failIssue = true;

        assertThrows(SecurityException.class, () -> fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1"));

        assertTrue(fixture.store.values.isEmpty());
    }

    @Test
    void rotationTokenFailureKeepsOldKeyAndOldBearerUsable() throws Exception {
        Fixture fixture = fixture(HostedAuditSink.embeddedNoop());
        String oldBearer = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();
        fixture.identities.failIssue = true;

        assertThrows(SecurityException.class, () -> fixture.service.rotateKey(
                oldBearer, "rotate-1", "rotated", Duration.ofHours(1)));

        assertEquals("primary", fixture.store.values.get(fixture.tenant).keyId());
        fixture.identities.failIssue = false;
        assertEquals(fixture.tenant, fixture.service.tenant(oldBearer).tenantId());
    }

    @Test
    void invalidReplacementLifetimeCannotActivateNewKey() throws Exception {
        Fixture fixture = fixture(HostedAuditSink.embeddedNoop());
        String oldBearer = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();

        assertThrows(IllegalArgumentException.class, () -> fixture.service.rotateKey(
                oldBearer, "rotate-1", "rotated", Duration.ZERO));

        assertEquals("primary", fixture.store.values.get(fixture.tenant).keyId());
        assertEquals(fixture.tenant, fixture.service.tenant(oldBearer).tenantId());
    }

    @Test
    void postCommitAuditExportFailureCannotStrandBootstrapOrRotation() throws Exception {
        HostedAuditSink failingSink = event -> {
            throw new IOException("injected audit export failure");
        };
        Fixture fixture = fixture(failingSink);

        String oldBearer = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();
        HostedControlPlaneService.RotateKeyResult rotated = fixture.service.rotateKey(
                oldBearer, "rotate-1", "rotated", Duration.ofHours(1));

        assertEquals("rotated", rotated.state().keyId());
        assertThrows(SecurityException.class, () -> fixture.service.tenant(oldBearer));
        assertEquals(fixture.tenant,
                fixture.service.tenant(rotated.replacementBearerToken()).tenantId());
    }

    private static Fixture fixture(HostedAuditSink auditSink) {
        InMemoryStore store = new InMemoryStore();
        HostedTenantKeyProvider keys = (tenantId, keyId, purpose) -> {
            try {
                byte[] value = MessageDigest.getInstance("SHA-256")
                        .digest((tenantId + ":" + keyId + ":" + purpose)
                                .getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(
                        value,
                        purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION
                                ? "AES"
                                : "HmacSHA256");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        SwitchableIdentityProvider identities = new SwitchableIdentityProvider(keys);
        UUID tenant = UUID.randomUUID();
        HostedControlPlaneService service = new HostedControlPlaneService(
                store,
                identities,
                keys,
                (projectId, snapshotId) -> { },
                auditSink,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(tenant, store, identities, service);
    }

    private record Fixture(
            UUID tenant,
            InMemoryStore store,
            SwitchableIdentityProvider identities,
            HostedControlPlaneService service
    ) { }

    private static final class SwitchableIdentityProvider implements HostedIdentityProvider {
        private final HmacHostedIdentityProvider delegate;
        private boolean failIssue;

        private SwitchableIdentityProvider(HostedTenantKeyProvider keys) {
            this.delegate = new HmacHostedIdentityProvider(keys);
        }

        @Override
        public String issue(
                UUID tenantId,
                String principalId,
                String keyId,
                Instant issuedAt,
                Duration lifetime,
                String tokenId
        ) {
            if (failIssue) {
                throw new SecurityException("injected token issuance failure");
            }
            return delegate.issue(tenantId, principalId, keyId, issuedAt, lifetime, tokenId);
        }

        @Override
        public HostedAccessClaims authenticate(String bearerToken, Instant now) {
            return delegate.authenticate(bearerToken, now);
        }
    }

    private static final class InMemoryStore implements HostedControlPlaneStore {
        private final Map<UUID, HostedTenantState> values = new HashMap<>();

        @Override
        public void create(HostedTenantState state) throws IOException {
            if (values.putIfAbsent(state.tenantId(), state) != null) {
                throw new IOException("exists");
            }
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) {
            return Optional.ofNullable(values.get(tenantId));
        }

        @Override
        public void save(HostedTenantState state, long expectedVersion) throws IOException {
            HostedTenantState current = values.get(state.tenantId());
            if (current == null || current.version() != expectedVersion) {
                throw new IOException("concurrent modification");
            }
            values.put(state.tenantId(), state);
        }
    }
}
