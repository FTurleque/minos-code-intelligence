package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.hosted.HostedTenantKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalMinosTeamApiTest {
    @TempDir Path home;

    @Test
    void exposesTenantIsolatedControlPlaneThroughJdkOnlyDtos() throws Exception {
        MinosApplication app = MinosApplication.builder(home).hostedTenantKeyProvider(keys()).build();
        MinosTeamApi api = new LocalMinosApi(app).team();
        UUID tenantId = UUID.randomUUID();
        var bootstrap = api.bootstrap(new MinosTeamApi.BootstrapRequest(tenantId, "Acme", "key-a", "owner",
                "Owner", Duration.ofHours(1), "api-bootstrap"));
        assertEquals("SECRET_OUTPUT_ONCE_DO_NOT_LOG", bootstrap.tokenHandling());
        String token = bootstrap.bearerToken();
        var workspace = api.createWorkspace(token, "api-workspace", "Platform");
        assertEquals(tenantId, workspace.tenantId());
        assertEquals(1, api.listWorkspaces(token).size());
        assertEquals("TENANT_SCOPED", api.tenant(token).isolation());
        assertFalse(api.audit(token, 20).isEmpty());
        MinosApi.MinosApiException denied = assertThrows(MinosApi.MinosApiException.class,
                () -> api.tenant("invalid-token"));
        assertEquals(MinosApi.ErrorCode.ACCESS_DENIED, denied.code());
    }

    @Test
    void localModeRemainsAvailableWhileTeamOperationsFailClosed() throws Exception {
        MinosTeamApi api = new LocalMinosApi(MinosApplication.builder(home).build()).team();
        MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                () -> api.tenant("not-a-token"));
        assertEquals(MinosApi.ErrorCode.UNAVAILABLE, failure.code());
    }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) java.util.Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
    }
}
