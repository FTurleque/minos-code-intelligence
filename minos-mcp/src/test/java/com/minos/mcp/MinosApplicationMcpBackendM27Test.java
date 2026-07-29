package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.hosted.HostedTenantKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosApplicationMcpBackendM27Test {
    @Test
    void hostedToolsAreReadOnlyTenantScopedAndTakeCredentialsOnlyFromSupplier(@TempDir Path home) throws Exception {
        HostedTenantKeyProvider keys = (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) java.util.Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes,
                    purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
        MinosApplication app = MinosApplication.builder(home).hostedTenantKeyProvider(keys).build();
        var service = app.hostedControlPlaneService().orElseThrow();
        var bootstrap = service.bootstrap(UUID.randomUUID(), "Acme", "key-a", "owner", "Owner",
                Duration.ofHours(1), "mcp-bootstrap");
        var workspace = service.createWorkspace(bootstrap.bearerToken(), "mcp-workspace", "Platform");
        MinosApplicationMcpBackend backend = new MinosApplicationMcpBackend(app, bootstrap::bearerToken);

        assertTrue(backend.teamTenant().contains("TENANT_SCOPED"));
        assertTrue(backend.teamWorkspaces().contains("Platform"));
        assertTrue(backend.teamWorkspace(workspace.workspaceId().toString()).contains("Platform"));
        assertTrue(backend.teamMembers().contains("owner"));
        assertTrue(backend.teamAudit(20).contains("WORKSPACE_CREATE"));

        MinosApplicationMcpBackend missing = new MinosApplicationMcpBackend(app, () -> null);
        assertThrows(SecurityException.class, missing::teamTenant);
    }
}
