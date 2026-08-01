package com.minos.hosted;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedProductionBoundaryTest {

    @Test
    void embeddedBoundaryDoesNotClaimTransportAvailabilitySaasOrProcessIsolation() {
        HostedProductionBoundary boundary = HostedProductionBoundary.embeddedLocalFirst(false);

        assertEquals(HostedProductionBoundary.Mode.EMBEDDED_LOCAL_FIRST, boundary.mode());
        assertEquals(HostedProductionBoundary.PortDisposition.NOT_PROVIDED, boundary.transportTls());
        assertEquals(HostedProductionBoundary.PortDisposition.NOT_PROVIDED, boundary.backupAvailability());
        assertEquals(HostedProductionBoundary.PortDisposition.EMBEDDED_NOOP, boundary.auditSink());
        assertTrue(boundary.limitations().contains("HOSTED_SAAS_OPERATION_NOT_CLAIMED"));
        assertTrue(boundary.limitations().contains("HOSTED_PROCESS_ISOLATION_NOT_QUALIFIED"));
    }

    @Test
    void externalAuditSinkReceivesPersistedAllowedAndDeniedEvents() throws Exception {
        InMemoryStore store = new InMemoryStore();
        HostedTenantKeyProvider keys = keys();
        List<HostedAuditEvent> exported = new ArrayList<>();
        HostedControlPlaneService service = new HostedControlPlaneService(
                store,
                new HmacHostedIdentityProvider(keys),
                keys,
                (project, snapshot) -> { },
                exported::add,
                Clock.fixed(Instant.parse("2026-07-31T07:00:00Z"), ZoneOffset.UTC));
        UUID tenant = UUID.randomUUID();
        String owner = service.bootstrap(
                tenant, "Team", "primary", "owner", "Owner",
                Duration.ofHours(1), "bootstrap-1").bearerToken();
        service.grantMember(owner, "grant-1", "viewer", "Viewer", HostedRole.VIEWER);
        String viewer = service.issueToken(owner, "token-1", "viewer", Duration.ofHours(1));
        assertThrows(SecurityException.class,
                () -> service.createWorkspace(viewer, "denied-1", "Forbidden"));

        HostedTenantState persisted = store.values.get(tenant);
        assertEquals(
                persisted.auditEvents().stream().map(HostedAuditEvent::hash).toList(),
                exported.stream().map(HostedAuditEvent::hash).toList());
        assertEquals(HostedProductionBoundary.PortDisposition.OPERATOR_ADAPTER,
                service.productionBoundary().auditSink());
        assertTrue(exported.stream().anyMatch(event ->
                event.outcome() == HostedAuditEvent.Outcome.DENIED
                        && "denied-1".equals(event.requestId())));
    }

    @Test
    void facadeStaysThinAndCohesiveServicesAreRealSourceFiles(@TempDir Path ignored) throws Exception {
        Path sourceRoot = Path.of("minos-application/src/main/java/com/minos/hosted");
        String facade = Files.readString(sourceRoot.resolve("HostedControlPlaneService.java"));
        assertTrue(facade.lines().count() <= 260L, "hosted facade must remain thin");
        for (String component : List.of(
                "HostedTenantService.java",
                "HostedMembershipService.java",
                "HostedWorkspaceService.java",
                "HostedRetentionService.java",
                "HostedTokenService.java",
                "HostedAuthorizationService.java",
                "HostedAuditChain.java",
                "HostedTenantMutationWriter.java")) {
            assertTrue(Files.isRegularFile(sourceRoot.resolve(component)), "missing hosted component " + component);
        }
    }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            try {
                byte[] value = MessageDigest.getInstance("SHA-256")
                        .digest((tenantId + ":" + keyId + ":" + purpose)
                                .getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(
                        value,
                        purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
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
