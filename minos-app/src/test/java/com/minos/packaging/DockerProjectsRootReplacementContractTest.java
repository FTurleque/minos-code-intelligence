package com.minos.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changing the Docker projects root of an installed runtime replaces the project path mapping that minos-bootstrap
 * saved, which detaches the projects outside the new root. Every entry point (wizard, switcher, ZIP install) compares
 * the chosen root with the recorded one before writing anything, refuses an unconfirmed change, and forwards the
 * confirmation down to {@code configure-project-paths --replace}.
 */
class DockerProjectsRootReplacementContractTest {

    private static final String FUNCTION = "Get-RecordedDockerProjectsRoot";
    private static final String CALL = "$RecordedProjectsRoot = " + FUNCTION
            + " -DockerDataRoot $DockerDataRoot -ProjectsRoot $ProjectsRoot";

    /** label -> mapping file content ('-' for no file), chosen root, expected result ('' when no change). */
    private static final Map<String, String[]> CASES = cases(
            "noMapping", new String[]{"-", "N:\\workspace-dev", ""},
            "sameRoot", new String[]{"formatVersion=1\nhostRoot=N\\:/workspace-dev\ncontainerRoot=/workspace/projects\n",
                    "N:\\workspace-dev\\", ""},
            "sameRootOtherCase", new String[]{"hostRoot=n\\:/WORKSPACE-dev\n", "N:\\workspace-dev", ""},
            "otherRoot", new String[]{"#MINOS project path mapping v1\ncontainerRoot=/workspace/projects\n"
                    + "formatVersion=1\nhostRoot=C\\:/Users/alice\n", "N:\\workspace-dev", "C:/Users/alice"},
            "noHostRoot", new String[]{"formatVersion=1\n", "N:\\workspace-dev", ""});

    @Test
    void scriptsCompareTheRecordedRootBeforeWritingAndForwardTheConfirmation() throws IOException {
        Path root = repoRoot();
        String switcher = text(root.resolve("scripts/install/switch-mcp-backend.ps1"));
        String zip = text(root.resolve("scripts/install/install-windows.ps1"));

        for (String script : List.of(switcher, zip)) {
            assertTrue(script.contains("function " + FUNCTION + "("));
            assertTrue(script.contains("[switch] $ReplaceDockerProjectsRoot"));
            assertTrue(script.contains(CALL));
            assertTrue(script.contains("/REPLACEDOCKERPROJECTSROOT=1"), "the refusal names the silent confirmation");
            assertTrue(script.contains("-ReplaceDockerProjectsRoot"), "the refusal names the script confirmation");
        }
        assertTrue(zip.indexOf(CALL) < zip.indexOf("Expand-Archive"), "the ZIP install compares before extracting");
        assertTrue(zip.contains("$SwitchParameters['ReplaceDockerProjectsRoot'] = $true"));

        int compare = switcher.indexOf(CALL);
        assertTrue(compare > 0 && compare < switcher.indexOf("& $DockerConfiguratorPath @DockerParameters"),
                "the switcher compares before preparing the Docker runtime");
        assertTrue(compare < switcher.indexOf("New-Item -ItemType Directory -Force -Path $RuntimeRoot"),
                "the switcher compares before writing its runtime folder");
        assertTrue(switcher.contains("$DockerParameters['ReplaceProjectsRoot'] = $true"));
        assertTrue(switcher.contains("-and -not $ReplaceProjectsRoot"), "a root change never reuses the runtime");
        int snapshot = switcher.indexOf("$ProjectRootSnapshot = New-ProjectRootSnapshot");
        assertTrue(snapshot > 0 && snapshot < switcher.indexOf("& $DockerConfiguratorPath @DockerParameters"),
                "the mapping and the registry are saved before the bootstrap rewrites them");
        assertTrue(between(switcher, "catch {\n    $Failure = $_", "throw $Failure")
                .contains("Restore-ProjectRootSnapshot -Snapshot $ProjectRootSnapshot"),
                "a failed switch restores the mapping and the registry");
        assertTrue(switcher.contains("storageBackend=postgresql"), "a PostgreSQL runtime refuses the root change");

        String configurator = text(root.resolve("docker/scripts/configure-docker-mcp.ps1"));
        assertTrue(configurator.contains("[switch] $ReplaceProjectsRoot"));
        assertTrue(configurator.contains("$InstallParameters['ReplaceProjectsRoot'] = $true"));
        String release = text(root.resolve("docker/scripts/prod-mcp-release.ps1"));
        assertTrue(release.contains("[switch] $ReplaceProjectsRoot"));
        assertTrue(release.contains("$LifecycleArguments.ReplaceProjectsRoot = $true"));
        String lifecycle = text(root.resolve("docker/scripts/mcp-lifecycle.ps1"));
        assertTrue(lifecycle.contains("[switch] $ReplaceProjectsRoot"));
        String install = between(lifecycle, "    'Install' {", "    'Start' {");
        assertTrue(install.contains("'minos-bootstrap', 'configure-project-paths', '--replace'"),
                "only the Install action replaces the mapping, and only on request");
        assertTrue(between(lifecycle, "    'Validate' {", "    'Stop' {")
                .contains("Compose @('run', '--rm', '--no-deps', 'minos-bootstrap')"), "Validate never replaces");

        for (String script : List.of(switcher, zip, configurator, release, lifecycle)) {
            assertTrue(script.chars().allMatch(c -> c < 128), "shipped PowerShell scripts stay ASCII");
        }
    }

    @Test
    void installerAsksBeforeReplacingAndForwardsTheConfirmation() throws IOException {
        String installer = text(repoRoot().resolve("packaging/windows/minos-installer.iss.template"));

        assertTrue(installer.contains("function RecordedDockerProjectsRoot(): String;"));
        assertTrue(installer.contains("'runtime\\project-paths.properties'"));
        String dockerPage = between(installer, "if (CurPageID = DockerProjectsPage.ID) and DockerMcpSelected() then",
                "procedure PromptForUserDataRemoval;");
        assertTrue(dockerPage.contains("RecordedRoot := RecordedDockerProjectsRoot();"));
        assertTrue(dockerPage.contains("mbConfirmation, MB_YESNO"), "the wizard asks before replacing the root");
        assertTrue(installer.contains("{param:REPLACEDOCKERPROJECTSROOT|0}"), "a silent install confirms by parameter");
        assertTrue(installer.contains("Parameters := Parameters + ' -ReplaceDockerProjectsRoot';"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void recordedRootUnderWindowsPowerShell(@TempDir Path work) throws Exception {
        Map<String, String> switcher = run(work.resolve("switcher"), "scripts/install/switch-mcp-backend.ps1");
        Map<String, String> zip = run(work.resolve("zip"), "scripts/install/install-windows.ps1");

        assertEquals(switcher, zip, "the switcher and the ZIP install must read alike");
        CASES.forEach((label, data) -> assertEquals(data[2].isEmpty() ? "NONE" : data[2], switcher.get(label), label));
    }

    /** Runs the real function, extracted by the PowerShell parser, on one Docker data root per case. */
    private static Map<String, String> run(Path work, String script) throws Exception {
        Files.createDirectories(work);
        StringBuilder input = new StringBuilder();
        for (Map.Entry<String, String[]> entry : CASES.entrySet()) {
            Path dataRoot = Files.createDirectories(work.resolve(entry.getKey()));
            if (!"-".equals(entry.getValue()[0])) {
                Files.createDirectories(dataRoot.resolve("runtime"));
                Files.writeString(dataRoot.resolve("runtime/project-paths.properties"), entry.getValue()[0],
                        StandardCharsets.ISO_8859_1);
            }
            input.append(entry.getKey()).append('|').append(dataRoot).append('|').append(entry.getValue()[1]).append('\n');
        }
        Path casesFile = work.resolve("cases.txt");
        Files.writeString(casesFile, input.toString(), StandardCharsets.UTF_8);
        Path harness = work.resolve("harness.ps1");
        Files.writeString(harness, harnessText(), StandardCharsets.UTF_8);

        String output = WindowsPowerShellHarness.run(work, harness, "-ScriptPath", repoRoot().resolve(script).toString(),
                "-CasesPath", casesFile.toString());
        Map<String, String> results = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) results.put(line.substring(0, separator), line.substring(separator + 1).trim());
        }
        assertEquals(CASES.keySet(), results.keySet(), script + " harness output:\n" + output);
        return results;
    }

    private static String harnessText() {
        return """
                param([string] $ScriptPath, [string] $CasesPath)
                $ErrorActionPreference = 'Stop'
                $tokens = $null; $errors = $null
                $ast = [System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref] $tokens, [ref] $errors)
                if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
                $isTarget = { param($n)
                    $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq '%1$s' }
                $found = @($ast.FindAll($isTarget, $true))
                if ($found.Count -ne 1) { throw "expected one definition, found $($found.Count)" }
                . ([scriptblock]::Create($found[0].Extent.Text))
                foreach ($line in [System.IO.File]::ReadAllLines($CasesPath)) {
                    if ([string]::IsNullOrWhiteSpace($line)) { continue }
                    $parts = $line.Split('|')
                    $recorded = %1$s -DockerDataRoot $parts[1] -ProjectsRoot $parts[2]
                    if ([string]::IsNullOrEmpty($recorded)) { $recorded = 'NONE' }
                    [Console]::Out.WriteLine($parts[0] + '=' + $recorded)
                }
                """.formatted(FUNCTION);
    }

    private static Map<String, String[]> cases(Object... labelsAndValues) {
        Map<String, String[]> cases = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            cases.put((String) labelsAndValues[i], (String[]) labelsAndValues[i + 1]);
        }
        return cases;
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from);
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
