package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29InstallerBackendLifecycleContractTest {

    @Test
    void installerAndZipUseOneTransactionalBackendLifecycle() throws Exception {
        Path root = repoRoot();
        String switcher = text(root.resolve("scripts/install/switch-mcp-backend.ps1"));
        String probe = text(root.resolve("scripts/install/probe-mcp-backend.ps1"));
        String lifecycle = text(root.resolve("scripts/install/verify-mcp-backend-lifecycle.ps1"));
        String installer = text(root.resolve("packaging/windows/minos-installer.iss.template"));
        String distribution = text(root.resolve("scripts/release/build-windows-distribution.ps1"));
        String builder = text(root.resolve("scripts/release/build-windows-installer.ps1"));
        String zipInstaller = text(root.resolve("scripts/install/install-windows.ps1"));

        assertOrdered(switcher,
                "PREPARE target=docker result=success",
                "HANDSHAKE target=$TargetBackend result=success",
                "COMMIT backend=$TargetBackend result=success",
                "RETIRE backend=docker action=stop result=success");
        assertTrue(switcher.contains("Restore-BackendConfiguration"));
        assertTrue(switcher.contains("New-DockerRuntimeSnapshot"));
        assertTrue(switcher.contains("Restore-DockerRuntimeSnapshot"));
        assertTrue(switcher.contains("ROLLBACK upgrade=docker runtime=restored action=start result=success"));
        assertTrue(switcher.contains("MINOS MCP BACKEND SWITCH SUCCESS"));
        assertFalse(switcher.toLowerCase().contains("fallback to native"));

        assertTrue(probe.contains("notifications/initialized"));
        assertTrue(probe.contains("tools/list"));
        assertTrue(probe.contains("minos_search_code"));
        assertTrue(probe.contains("minos_impact"));
        assertTrue(probe.contains("MINOS MCP BACKEND HANDSHAKE SUCCESS"));

        for (String proof : new String[]{
                "Fresh native backend selection was not committed",
                "Native -> Docker switch was not committed",
                "Docker -> native switch was not committed",
                "Failed Docker handshake changed the active backend",
                "Retirement failure did not restore the previous Docker config",
                "Docker same-backend upgrade did not prepare a new runtime generation",
                "Failed Docker upgrade did not restore the previous runtime generation",
                "Backend lifecycle modified unrelated third-party client configuration"}) {
            assertTrue(lifecycle.contains(proof), "missing S7 lifecycle proof: " + proof);
        }
        assertTrue(lifecycle.contains("MINOS MCP BACKEND TRANSACTIONAL LIFECYCLE VERIFICATION SUCCESS"));

        assertTrue(installer.contains("McpModePage := CreateInputOptionPage("));
        assertTrue(installer.contains("    True,\n    True);"),
                "backend page must be exclusive and require one selection");
        assertTrue(installer.contains("McpModePage.Values[2]"));
        assertTrue(installer.contains("Ne pas configurer maintenant"));
        assertTrue(installer.contains("ExistingMcpBackend"));
        assertTrue(installer.contains("LoadStringFromFile(ConfigPath, ConfigText)"));
        assertTrue(installer.contains("integration\\switch-mcp-backend.ps1"));
        assertTrue(installer.contains("if ConfigureSelectedMcpBackend() then\n      ConfigureMcpClients;"));
        assertTrue(installer.contains("if not DockerReady() then"));
        assertTrue(installer.contains("aucun fallback silencieux"));
        assertFalse(installer.contains("ConfigureDockerMcp;"));
        assertFalse(installer.contains("ConfigureNativeMcpClients;"));

        for (String packaged : new String[]{"probe-mcp-backend.ps1", "switch-mcp-backend.ps1"}) {
            assertTrue(distribution.contains("'" + packaged + "'"), "distribution omits " + packaged);
            assertTrue(builder.contains("integration\\" + packaged), "setup builder omits " + packaged);
            assertTrue(zipInstaller.contains("integration\\" + packaged), "ZIP installer omits " + packaged);
        }
        assertTrue(distribution.contains("verify-mcp-backend-lifecycle.ps1"));
        assertTrue(zipInstaller.contains("[ValidateSet('none', 'native', 'docker')]"));
        assertTrue(zipInstaller.contains("TargetBackend = $McpBackend"));
        assertOrdered(zipInstaller,
                "Copy-Item -LiteralPath $Source -Destination $InstallRoot -Recurse",
                "& $Switcher @SwitchParameters",
                "[Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')");
        assertTrue(zipInstaller.contains("Move-Item -LiteralPath $Backup -Destination $InstallRoot"));
    }

    private static void assertOrdered(String text, String... tokens) {
        int cursor = -1;
        for (String token : tokens) {
            int index = text.indexOf(token, cursor + 1);
            assertTrue(index > cursor, "missing/out-of-order lifecycle token: " + token);
            cursor = index;
        }
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
