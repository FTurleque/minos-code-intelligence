package com.minos.hosted;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedControlPlaneServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void authenticatesAndEnforcesRbacWhileAuditingDeniedMutations() throws Exception {
        Fixture fixture = fixture();
        var bootstrap = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1");
        String owner = bootstrap.bearerToken();
        SharedWorkspace workspace = fixture.service.createWorkspace(owner, "create-1", "Shared");
        fixture.service.grantMember(owner, "grant-1", "viewer", "Viewer", HostedRole.VIEWER);
        String viewer = fixture.service.issueToken(owner, "token-1", "viewer", Duration.ofHours(1));

        assertEquals(List.of(workspace.workspaceId()), fixture.service.listWorkspaces(viewer).stream()
                .map(SharedWorkspace::workspaceId).toList());
        assertThrows(SecurityException.class,
                () -> fixture.service.createWorkspace(viewer, "denied-1", "Forbidden"));
        List<HostedAuditEvent> audit = fixture.service.audit(owner, 20);
        assertTrue(audit.stream().anyMatch(event -> event.requestId().equals("denied-1")
                && event.outcome() == HostedAuditEvent.Outcome.DENIED));
        assertFalse(audit.stream().anyMatch(event -> event.resourceId().contains("mht1.")));
    }

    @Test
    void rejectsTamperedExpiredAndCrossTenantAccess() throws Exception {
        Fixture fixture = fixture();
        var first = fixture.service.bootstrap(
                fixture.tenant, "First", "primary", "owner", "Owner",
                Duration.ofSeconds(1), "bootstrap-1");
        UUID otherTenant = UUID.randomUUID();
        var second = fixture.service.bootstrap(
                otherTenant, "Second", "primary", "other", "Other",
                Duration.ofHours(1), "bootstrap-2");
        SharedWorkspace otherWorkspace = fixture.service.createWorkspace(
                second.bearerToken(), "create-2", "Other space");

        char last = first.bearerToken().charAt(first.bearerToken().length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        String tampered = first.bearerToken().substring(0, first.bearerToken().length() - 1) + replacement;
        assertThrows(SecurityException.class, () -> fixture.service.listWorkspaces(tampered));
        fixture.clock.advance(Duration.ofSeconds(2));
        assertThrows(SecurityException.class,
                () -> fixture.service.listWorkspaces(first.bearerToken()));

        fixture.clock.advance(Duration.ofSeconds(-2));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.workspace(
                        first.bearerToken(), otherWorkspace.workspaceId()));
    }

    @Test
    void rejectsPersistedAuditEventWithInvalidHmac() throws Exception {
        Fixture fixture = fixture();
        String owner = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();
        HostedTenantState state = fixture.store.values.get(fixture.tenant);
        HostedAuditEvent original = state.auditEvents().getFirst();
        String forgedHash = original.hash().equals("0".repeat(64))
                ? "f".repeat(64)
                : "0".repeat(64);
        HostedAuditEvent forged = new HostedAuditEvent(
                original.sequence(),
                original.tenantId(),
                original.occurredAt(),
                original.principalId(),
                original.action(),
                original.resourceType(),
                original.resourceId(),
                original.outcome(),
                original.requestId(),
                original.keyId(),
                original.previousHash(),
                forgedHash);
        HostedTenantState tampered = new HostedTenantState(
                state.tenantId(),
                state.name(),
                state.keyId(),
                state.version(),
                state.createdAt(),
                state.updatedAt(),
                state.retentionPolicy(),
                state.members(),
                state.workspaces(),
                state.auditSequence(),
                state.auditAnchorHash(),
                List.of(forged));
        fixture.store.values.put(fixture.tenant, tampered);

        assertThrows(SecurityException.class,
                () -> fixture.service.listWorkspaces(owner));
    }

    @Test
    void preservesLastOwnerAndVerifiesExactSnapshotBindings() throws Exception {
        Fixture fixture = fixture();
        String owner = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();
        SharedWorkspace workspace = fixture.service.createWorkspace(owner, "create-1", "Shared");
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.revokeMember(owner, "revoke-1", "owner"));

        UUID project = UUID.randomUUID();
        HostedProjectBinding binding = fixture.service.bindProject(
                owner, "bind-1", workspace.workspaceId(), project, "snapshot-exact");
        assertEquals(project, binding.projectId());
        assertEquals(project + ":snapshot-exact", fixture.lastVerifiedBinding);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.bindProject(
                        owner, "bind-2", workspace.workspaceId(), project, "snapshot-other"));
    }

    @Test
    void rotatesEncryptionAndSigningKeyAndInvalidatesOldToken() throws Exception {
        Fixture fixture = fixture();
        String oldToken = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();
        var rotated = fixture.service.rotateKey(
                oldToken, "rotate-1", "rotated", Duration.ofHours(1));

        assertEquals("rotated", rotated.state().keyId());
        assertThrows(SecurityException.class,
                () -> fixture.service.listWorkspaces(oldToken));
        assertEquals(fixture.tenant,
                fixture.service.tenant(rotated.replacementBearerToken()).tenantId());
        assertNotEquals(oldToken, rotated.replacementBearerToken());
    }

    @Test
    void plansAndAppliesRetentionOnlyWhenExplicitlyRequested() throws Exception {
        Fixture fixture = fixture();
        String owner = fixture.service.bootstrap(
                fixture.tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(24), "bootstrap-1").bearerToken();
        SharedWorkspace workspace = fixture.service.createWorkspace(owner, "create-1", "Shared");
        fixture.service.archiveWorkspace(owner, "archive-1", workspace.workspaceId());
        fixture.service.setRetention(
                owner, "policy-1", new HostedRetentionPolicy(100, 1, 1));
        fixture.clock.advance(Duration.ofHours(23));
        owner = fixture.service.issueToken(
                owner, "refresh-1", "owner", Duration.ofHours(24));
        fixture.clock.advance(Duration.ofHours(2));

        HostedRetentionPlan plan = fixture.service.retentionPlan(owner);
        assertTrue(plan.auditEventsToRemove() > 0);
        assertEquals(List.of(workspace.workspaceId()), plan.archivedWorkspacesToRemove());
        assertEquals(1, fixture.service.listWorkspaces(owner).size());
        var applied = fixture.service.applyRetention(owner, "apply-1");
        assertTrue(applied.state().workspaces().isEmpty());
        assertEquals("RETENTION_APPLY", applied.state().auditEvents().getLast().action());
    }

    private static Fixture fixture() {
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
        MutableClock clock = new MutableClock(NOW);
        Fixture fixture = new Fixture(UUID.randomUUID(), store, keys, clock);
        HostedBindingVerifier verifier = (project, snapshot) ->
                fixture.lastVerifiedBinding = project + ":" + snapshot;
        fixture.service = new HostedControlPlaneService(
                store, new HmacHostedIdentityProvider(keys), keys, verifier, clock);
        return fixture;
    }

    private static final class Fixture {
        private final UUID tenant;
        private final InMemoryStore store;
        private final HostedTenantKeyProvider keys;
        private final MutableClock clock;
        private HostedControlPlaneService service;
        private String lastVerifiedBinding;

        private Fixture(
                UUID tenant,
                InMemoryStore store,
                HostedTenantKeyProvider keys,
                MutableClock clock
        ) {
            this.tenant = tenant;
            this.store = store;
            this.keys = keys;
            this.clock = clock;
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
