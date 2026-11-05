package com.minos.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows uninstaller removes what the installation created: the containers, networks and (on purge) the volumes
 * of the Docker Compose project of the instance, including the services of the PostgreSQL and Ollama profiles, and
 * the Codex block bounded by the MINOS markers. Every Docker failure reaches the uninstall log; none is masked.
 */
class UninstallCleanupContractTest {

    private static final String REMOVER = "docker\\scripts\\remove-docker-instance.ps1";

    @Test
    void uninstallerRemovesTheComposeProjectBeforeTheProgramFilesAndTheData() throws IOException {
        String installer = text(repoRoot().resolve("packaging/windows/minos-installer.iss.template"));

        assertTrue(installer.contains("UninstallLogging=yes"), "the uninstaller must always write its log");
        assertTrue(installer.contains("Type: dirifempty; Name: \"{userprograms}\\MINOS\""),
                "the Start menu group folder must be removed once empty");
        assertFalse(installer.contains("docker volume rm"),
                "volumes must not be removed by a bare docker call while containers still mount them");

        String postUninstall = between(installer, "else if CurUninstallStep = usPostUninstall then", "end;\nend;");
        assertOrdered(postUninstall, "RemoveManagedDockerInstance;", "RemoveMinosProgramPayload;",
                "DeleteMinosUserData;");

        String removal = between(installer, "procedure RemoveManagedDockerInstance;", "\nend;\n");
        assertTrue(removal.contains(REMOVER), "the removal must call the shipped script");
        assertTrue(removal.contains("' -ComposeProject \"' + InstanceName"), "the removal must name the instance");
        assertTrue(removal.contains("if DeleteUserDataOnUninstall then"),
                "volumes may only be removed when the user chose the purge");
        assertTrue(removal.contains("Parameters := Parameters + ' -Purge -RemoveImages';"),
                "the purge must remove the volumes and the unused MINOS images");
        assertFalse(removal.replace("' -Purge -RemoveImages'", "").contains("-RemoveImages"),
                "images may only be removed on purge");
        String prompt = between(installer, "procedure PromptForUserDataRemoval;", "\nend;\n");
        assertTrue(prompt.contains("images Docker MINOS"), "the purge confirmation must announce the image removal");
        assertTrue(removal.contains("LogScriptOutput("), "the removal output must reach the uninstall log");
        assertTrue(removal.contains("if ResultCode = 0 then exit;"), "a failed removal must be detected by its exit code");
        assertFalse(removal.contains(">NUL"), "no Docker failure may be masked by a redirection");
        assertTrue(removal.contains("SuppressibleMsgBox("), "a failed removal must be reported to the user");

        String clients = between(installer, "procedure RemoveMcpClientIntegrations;", "\nend;\n");
        assertTrue(clients.contains("LogScriptOutput("), "MCP client removal warnings must reach the uninstall log");
        assertTrue(clients.contains("WarningRecord"), "a warning of the client removal must not be swallowed");
    }

    @Test
    void lifecycleUninstallStopsTheProfileServicesAndKeepsTheExternalVolumes() throws IOException {
        Path root = repoRoot();
        String lifecycle = text(root.resolve("docker/scripts/mcp-lifecycle.ps1"));
        String uninstall = between(lifecycle, "    'Uninstall' {", "\n    }\n");
        assertTrue(uninstall.contains(
                "Compose @('--profile', 'postgresql', '--profile', 'ollama', 'down', '--timeout', '10', '--remove-orphans', '--volumes')"),
                "compose down must include the PostgreSQL and Ollama profiles");

        String connected = text(root.resolve("docker/compose-mcp.connected.yaml"));
        assertTrue(connected.contains("  minos-postgres-data:\n    external: true"),
                "the PostgreSQL volume must stay external: down --volumes must never remove it");
        assertTrue(connected.contains("  minos-ollama-models:\n    external: true"),
                "the Ollama volume must stay external: down --volumes must never remove it");
    }

    @Test
    void distributionShipsAndQualifiesTheRemovalScript() throws IOException {
        Path root = repoRoot();
        String distribution = text(root.resolve("scripts/release/build-windows-distribution.ps1"));
        String builder = text(root.resolve("scripts/release/build-windows-installer.ps1"));
        assertTrue(distribution.contains("'remove-docker-instance.ps1'"), "the removal script must be shipped");
        assertTrue(distribution.contains("'scripts\\install\\verify-docker-instance-removal.ps1'"),
                "every distribution build must qualify the removal script");
        assertTrue(distribution.contains("'scripts\\install\\verify-codex-mcp-integration.ps1'"),
                "every distribution build must qualify the Codex integration removal");
        assertTrue(builder.contains("'" + REMOVER + "'"), "the installer builder must require the removal script");

        byte[] script = Files.readAllBytes(root.resolve("docker/scripts/remove-docker-instance.ps1"));
        for (byte value : script) {
            assertTrue(value >= 0, "remove-docker-instance.ps1 must be ASCII for Windows PowerShell 5.1");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void removalScriptAndCodexCleanupPassTheirVerifiersUnderWindowsPowerShell(@TempDir Path work) throws Exception {
        Path root = repoRoot();
        assertTrue(runWindowsPowerShell(work, root.resolve("scripts/install/verify-docker-instance-removal.ps1"))
                .contains("MINOS DOCKER INSTANCE REMOVAL VERIFICATION SUCCESS"));
        assertTrue(runWindowsPowerShell(work, root.resolve("scripts/install/verify-codex-mcp-integration.ps1"))
                .contains("MINOS CODEX MCP INTEGRATION VERIFICATION SUCCESS"));
    }

    /** Windows PowerShell 5.1 (the uninstaller's runtime) by absolute path, with an allow-listed environment. */
    private static String runWindowsPowerShell(Path work, Path script) throws Exception {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        ProcessBuilder builder = new ProcessBuilder(List.of(powershell.toString(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = Map.copyOf(environment);
        environment.clear();
        for (String name : List.of("SystemRoot", "windir", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA",
                "APPDATA", "ComSpec", "OS", "PATHEXT")) {
            String value = inherited.get(name);
            if (value != null) environment.put(name, value);
        }
        // No host tool (docker, codex) may be resolved: the verifiers put their simulated tools first on PATH.
        environment.put("Path", systemRoot + "\\System32;" + systemRoot);
        Path outputFile = work.resolve(script.getFileName() + ".txt");
        builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(outputFile.toFile());
        Process process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("PowerShell verifier timed out: " + script.getFileName());
        }
        // Console output uses the OEM code page; the verdict lines are ASCII, so a byte-per-char decoding suffices.
        String output = new String(Files.readAllBytes(outputFile), StandardCharsets.ISO_8859_1);
        assertEquals(0, process.exitValue(), "PowerShell verifier failed:\n" + output);
        return output;
    }

    private static void assertOrdered(String text, String... needles) {
        int position = -1;
        for (String needle : needles) {
            int next = text.indexOf(needle, position + 1);
            assertTrue(next > position, "missing or out of order: " + needle);
            position = next;
        }
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, "section not found: " + start);
        return text.substring(from, to);
    }

    private static String text(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/compose-mcp.prod.yaml"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
