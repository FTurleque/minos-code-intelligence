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
        String probeClient = text(root.resolve("minos-mcp/src/main/java/com/minos/mcp/MinosMcpHandshakeProbe.java"));
        String lifecycle = text(root.resolve("scripts/install/verify-mcp-backend-lifecycle.ps1"));
        String installer = text(root.resolve("packaging/windows/minos-installer.iss.template"));
        String distribution = text(root.resolve("scripts/release/build-windows-distribution.ps1"));
        String builder = text(root.resolve("scripts/release/build-windows-installer.ps1"));
        String zipInstaller = text(root.resolve("scripts/install/install-windows.ps1"));
        String runner = text(root.resolve("scripts/m29/run-s7.ps1"));

        assertOrdered(switcher,
                "PREPARE target=docker mode=install result=success",
                "HANDSHAKE target=$TargetBackend result=success",
                "COMMIT backend=$TargetBackend result=success",
                "RETIRE backend=docker action=stop result=success");
        assertTrue(switcher.contains("Test-ReusableDockerRuntime"));
        assertTrue(switcher.contains("PREPARE target=docker mode=reuse result=success"));
        assertTrue(switcher.contains("Invoke-DockerRuntimeAction -Action Validate"));
        assertTrue(switcher.contains("Restore-BackendConfiguration"));
        assertTrue(switcher.contains("New-DockerRuntimeSnapshot"));
        assertTrue(switcher.contains("Restore-DockerRuntimeSnapshot"));
        assertTrue(switcher.contains("ROLLBACK upgrade=docker runtime=restored action=start result=success"));
        assertTrue(switcher.contains("MINOS MCP BACKEND SWITCH SUCCESS"));
        assertFalse(switcher.toLowerCase().contains("fallback to native"));

        // The release probe must not maintain its own raw JSON-RPC framing. It
        // launches an SDK-backed probe with the Java runtime and fat JAR that are
        // actually shipped, while that Java probe negotiates initialize/tools/list
        // through the same MCP SDK client stack as production integration tests.
        assertTrue(probe.contains("app\\runtime\\bin\\java.exe"));
        assertTrue(probe.contains("lib\\minos.jar"));
        assertTrue(probe.contains("com.minos.mcp.MinosMcpHandshakeProbe"));
        assertTrue(probe.contains("MINOS MCP BACKEND HANDSHAKE SUCCESS"));
        assertTrue(probeClient.contains("McpClient.sync(transport)"));
        assertTrue(probeClient.contains("client.initialize();"));
        assertTrue(probeClient.contains("client.listTools();"));
        assertTrue(probeClient.contains("minos_search_code"));
        assertTrue(probeClient.contains("minos_impact"));
        assertTrue(probeClient.contains("MINOS MCP SDK HANDSHAKE SUCCESS"));

        for (String proof : new String[]{
                "Fresh native backend selection was not committed",
                "Native -> Docker switch was not committed",
                "Docker -> native switch was not committed",
                "Same-version native -> Docker rebuilt the managed runtime instead of reusing it",
                "Failed Docker handshake changed the active backend",
                "Retirement failure did not restore the previous Docker config",
                "Docker package upgrade did not prepare a new runtime generation",
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
        assertTrue(installer.contains(
                "if ConfigureSelectedMcpBackend() then\n      ConfigureMcpClients\n    else\n      RevertRuntimeSettingsToLocal;"));
        // A Docker-managed Postgres/Ollama selection is committed to MINOS's own
        // runtime settings before the Docker switch is proven -- if that switch then
        // fails and rolls back, those settings must be reverted too, or MINOS boots
        // pointed at a Postgres connection that was never provisioned.
        assertTrue(installer.contains("procedure RevertRuntimeSettingsToLocal;"));
        assertTrue(installer.contains(
                "if not (DockerMcpSelected() and (PostgreSqlSelected() or OllamaSelected())) then exit;"));
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

        // configure-docker-mcp.ps1 delegates to the M30 configurator whenever
        // StorageBackend=postgresql or SemanticProvider=ollama is selected, and that
        // configurator resolves compose.mcp.connected.yaml relative to its own directory.
        // Both must ship, or selecting managed PostgreSQL/Ollama fails at install time with
        // "M30 Docker service configurator is missing" -- which is exactly what happened
        // while these were absent from the distribution.
        for (String m30 : new String[]{
                "docker\\scripts\\configure-m30-docker-services.ps1",
                "docker\\compose.mcp.connected.yaml"}) {
            assertTrue(distribution.contains(m30), "distribution omits " + m30);
        }
        assertTrue(zipInstaller.contains("[ValidateSet('none', 'native', 'docker')]"));
        assertTrue(zipInstaller.contains("TargetBackend = $McpBackend"));
        assertTrue(zipInstaller.contains("DataRoot = $DataRoot"));
        assertTrue(zipInstaller.contains("DockerInstallRoot = $DockerInstallRoot"));
        assertTrue(zipInstaller.contains("DockerDataRoot = $DockerDataRoot"));
        assertTrue(zipInstaller.contains("yyyyMMdd-HHmmssfff"));
        assertTrue(zipInstaller.contains("$PreviousMarker"));
        assertTrue(zipInstaller.contains(".docker-mcp-managed"));
        assertTrue(zipInstaller.contains("$OriginalUserPath"));
        assertTrue(zipInstaller.contains("$PathChanged"));
        assertOrdered(zipInstaller,
                "Copy-Item -LiteralPath $Source -Destination $InstallRoot -Recurse",
                "[Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')",
                "& $Switcher @SwitchParameters");
        assertTrue(zipInstaller.contains("[Environment]::SetEnvironmentVariable('Path', $OriginalUserPath, 'User')"));
        assertTrue(zipInstaller.contains("Move-Item -LiteralPath $Backup -Destination $InstallRoot"));

        assertTrue(runner.contains("M29-S7 exact-head mismatch"));
        assertTrue(runner.contains("verify-mcp-backend-lifecycle.ps1"));
        assertTrue(runner.contains("verify-mcp-client-backend-routing.ps1"));
        assertTrue(runner.contains("verify-installer-template.ps1"));
        assertTrue(runner.contains("M29-S7 cheap contract gates SUCCESS"));
        assertTrue(runner.contains("build-windows-distribution.ps1"));
        assertTrue(runner.contains("build-windows-installer.ps1"));
        assertTrue(runner.contains("M29-S7 native-only install SUCCESS"));
        assertTrue(runner.contains("M29-S7 Docker-only install SUCCESS"));
        assertTrue(runner.contains("M29-S7 native -> Docker reuse SUCCESS"));
        assertTrue(runner.contains("M29-S7 uninstall-preserve SUCCESS"));
        assertTrue(runner.contains("M29-S7 explicit purge SUCCESS"));
        assertTrue(runner.contains("M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS"));
    }

    // The installer runs every shipped .ps1 through {sys}\WindowsPowerShell\v1.0\powershell.exe.
    // Windows PowerShell 5.1 decodes a BOM-less script as ANSI, so a UTF-8 non-ASCII character
    // is mis-decoded -- and an em dash becomes a sequence containing U+0094, which PowerShell
    // accepts as a closing smart quote. Inside a double-quoted string that silently truncates
    // the string and the whole script fails to PARSE, which is how managed PostgreSQL/Ollama
    // died with "Le terminateur " est manquant dans la chaine" on a real install. These files
    // ship without a BOM, so keep them ASCII-only.
    @Test
    void shippedPowerShellScriptsAreAsciiOnly() throws Exception {
        Path root = repoRoot();
        String distribution = text(root.resolve("scripts/release/build-windows-distribution.ps1"));

        StringBuilder offenders = new StringBuilder();
        int scanned = 0;
        for (String directory : new String[]{"scripts/install", "docker/scripts"}) {
            try (var entries = Files.list(root.resolve(directory))) {
                for (Path script : entries.filter(p -> p.toString().endsWith(".ps1")).toList()) {
                    String name = script.getFileName().toString();
                    if (!distribution.contains(name)) {
                        continue; // not part of the installed payload
                    }
                    scanned++;
                    String body = Files.readString(script);
                    for (int i = 0; i < body.length(); i++) {
                        if (body.charAt(i) > 127) {
                            offenders.append(directory).append('/').append(name)
                                    .append(" contains U+")
                                    .append(String.format("%04X", (int) body.charAt(i)))
                                    .append('\n');
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 0, "shipped-script scan matched nothing; the distribution list moved");
        assertTrue(offenders.isEmpty(),
                "shipped scripts must be ASCII-only for Windows PowerShell 5.1:\n" + offenders);
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
