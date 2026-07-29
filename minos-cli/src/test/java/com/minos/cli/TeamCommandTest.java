package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.hosted.HostedTenantKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamCommandTest {
    @TempDir Path home;

    @Test
    void bootstrapsAuthenticatesAndNeverAcceptsBearerTokenAsArgument() throws Exception {
        MinosApplication application = MinosApplication.builder(home)
                .hostedTenantKeyProvider(keys()).build();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(application.hostedControlPlaneService().orElseThrow(), token::get);
        UUID tenant = UUID.randomUUID();
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int bootstrap = command.run(new String[]{"bootstrap", "--tenant", tenant.toString(), "--name", "Acme",
                "--key-id", "key-a", "--owner", "alice", "--owner-name", "Alice",
                "--request-id", "req-bootstrap"}, output, error);
        assertEquals(0, bootstrap);
        assertTrue(output.toString().contains("\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\""));
        token.set(extract(output.toString(), "bearerToken"));

        output.setLength(0);
        assertEquals(0, command.run(new String[]{"workspace-create", "--name", "Platform",
                "--request-id", "req-workspace"}, output, error));
        assertTrue(output.toString().contains("\"name\":\"Platform\""));
        output.setLength(0);
        assertEquals(0, command.run(new String[]{"workspaces"}, output, error));
        assertTrue(output.toString().contains("\"isolation\":\"TENANT_SCOPED\""));

        error.setLength(0);
        String secret = "must-never-appear";
        assertEquals(2, command.run(new String[]{"tenant", "--token", secret}, output, error));
        assertFalse(error.toString().contains(secret));
        assertTrue(error.toString().contains("MINOS_TEAM_TOKEN"));
    }

    @Test
    void reportsDisabledTeamModeWithoutBreakingLocalCli() throws Exception {
        MinosApplication local = MinosApplication.builder(home).build();
        StringBuilder error = new StringBuilder();
        assertEquals(1, new MinosCli(
                new LocalProjectSymbolQuery(local), new LocalProjectOperations(local), local.architectureQuery(),
                local.impactQuery(), null, new LocalAutonomousIndexOperations(local), home, null, null, null,
                local.runtimeIntelligenceService(), null
        ).run(new String[]{"team", "tenant"}, new StringBuilder(), error));
        assertTrue(error.toString().contains("team is not configured"));
    }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
    }

    private static String extract(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":\\\"([^\\\"]+)\\\"").matcher(json);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}
