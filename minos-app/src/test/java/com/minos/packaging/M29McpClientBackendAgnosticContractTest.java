package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29McpClientBackendAgnosticContractTest {

    @Test
    void everySupportedClientTargetsStableMinosMcpEntrypoint() throws Exception {
        Path root = repoRoot();
        String manager = text(root.resolve("scripts/install/configure-mcp-clients.ps1"));
        String codex = text(root.resolve("scripts/install/configure-codex-mcp.ps1"));
        String verifier = text(root.resolve("scripts/install/verify-mcp-client-backend-routing.ps1"));
        String runner = text(root.resolve("scripts/m29/run-s6.ps1"));

        assertTrue(manager.contains("command = $MinosExe"));
        assertTrue(manager.contains("args = @('mcp')"));
        assertTrue(manager.contains("MINOS_HOME = $DataRoot"));
        assertTrue(manager.contains("$CopilotJetBrains"));
        assertTrue(manager.contains("$CopilotCli"));
        assertTrue(manager.contains("$ClaudeCode"));
        assertTrue(manager.contains("$ClaudeDesktop"));
        assertTrue(manager.contains("$Codex"));
        assertFalse(manager.toLowerCase().contains("docker exec"),
                "client integration must never own backend transport");

        assertTrue(codex.contains("'[mcp_servers.minos]'"));
        assertTrue(codex.contains("'command = ' + (Toml-String $MinosExe)"));
        assertTrue(codex.contains("'args = [\"mcp\"]'"));
        assertTrue(codex.contains("'MINOS_HOME = ' + (Toml-String $DataRoot)"));
        assertFalse(codex.toLowerCase().contains("docker exec"),
                "Codex integration must stay backend-agnostic");

        for (String client : new String[]{
                "Copilot JetBrains", "Copilot CLI", "Claude Code", "Claude Desktop", "Codex CLI/Desktop"}) {
            assertTrue(verifier.contains(client), "missing S6 client proof: " + client);
        }
        assertTrue(verifier.contains("backend.properties"));
        assertTrue(verifier.contains("native -> docker changes backend.properties only"));
        assertTrue(verifier.contains("client configs remain byte-identical"));
        assertTrue(verifier.contains("MINOS MCP BACKEND-AGNOSTIC CLIENT ROUTING VERIFICATION SUCCESS"));
        assertTrue(verifier.contains("Read-TomlBasicStringValue"),
                "Codex Desktop values must be decoded from serialized TOML before path comparison");
        assertTrue(verifier.contains("-Key 'command') -eq $ExpectedExe"));
        assertTrue(verifier.contains("-Key 'MINOS_HOME') -eq $DataRoot"));
        assertFalse(verifier.contains("$CodexText.IndexOf($ExpectedExe"),
                "raw TOML text cannot be compared to an unescaped Windows path");
        assertFalse(verifier.contains("$CodexText.IndexOf($DataRoot"),
                "raw TOML text cannot be compared to an unescaped MINOS_HOME path");

        assertTrue(runner.contains("M29-S6 exact-head mismatch"));
        assertTrue(runner.contains("verify-mcp-client-backend-routing.ps1"));
        assertTrue(runner.contains("verify-mcp-client-integration.ps1"));
        assertTrue(runner.contains("verify-mcp-client-preflight.ps1"));
        assertTrue(runner.contains("M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS"));
        assertTrue(runner.contains("${Relative}:"),
                "Windows PowerShell 5.1 requires braced interpolation before a literal colon");
        assertFalse(runner.contains("$Relative:"),
                "unbraced variable interpolation before ':' does not parse on Windows PowerShell 5.1");
    }

    private static String text(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/compose.mcp.prod.yaml"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
