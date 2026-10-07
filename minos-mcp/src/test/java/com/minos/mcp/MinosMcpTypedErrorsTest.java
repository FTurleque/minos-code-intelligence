package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.hosted.HostedTenantKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C01 : les échecs d'usage ou de configuration dont la cause est connue et sûre (mode équipe désactivé,
 * jeton d'équipe absent) sont rapportés par un message fixe, et non par l'erreur générique d'exécution d'outil.
 */
class MinosMcpTypedErrorsTest {

    @TempDir
    Path home;

    @Test
    void aTeamToolSaysThatTeamModeIsDisabled() throws Exception {
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            var result = McpToolCalls.call(new MinosMcpTools(new MinosApplicationMcpBackend(application)),
                    "minos_team_tenant", Map.of());

            assertTrue(McpToolCalls.isError(result));
            assertEquals("error: MINOS team mode is disabled", McpToolCalls.text(result));
        }
    }

    @Test
    void aTeamToolSaysThatATeamTokenIsRequiredWithoutEchoingAnyToken() throws Exception {
        try (MinosApplication application = MinosApplication.builder(home).hostedTenantKeyProvider(keys()).build()) {
            for (var token : new String[]{null, "", "   "}) {
                var result = McpToolCalls.call(
                        new MinosMcpTools(new MinosApplicationMcpBackend(application, () -> token)),
                        "minos_team_tenant", Map.of());

                assertTrue(McpToolCalls.isError(result));
                assertEquals("error: MINOS_TEAM_TOKEN is required for hosted MCP tools", McpToolCalls.text(result));
            }
        }
    }

    @Test
    void aRejectedTeamTokenStaysTheGenericErrorBecauseItsCauseIsNotShownToTheClient() throws Exception {
        try (MinosApplication application = MinosApplication.builder(home).hostedTenantKeyProvider(keys()).build()) {
            var result = McpToolCalls.call(
                    new MinosMcpTools(new MinosApplicationMcpBackend(application, () -> "mht1.not-a-valid-token")),
                    "minos_team_tenant", Map.of());

            assertTrue(McpToolCalls.isError(result));
            assertEquals("error: MINOS tool execution failed", McpToolCalls.text(result));
        }
    }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) java.util.Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
    }
}
