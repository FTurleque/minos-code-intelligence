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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Docker runtime mounts no projects root: each project is exposed by {@code prod-mcp-release.ps1 -Action AddProject}.
 * The Windows installer asks for no root, and the legacy settings ({@code /DOCKERPROJECTSROOT}, {@code -ProjectsRoot},
 * {@code -ReplaceDockerProjectsRoot}, the registry value) are ignored with a warning, never refused and never
 * forwarded. Every Docker install or upgrade saves the project mapping and the registry entries, which the
 * migration of a format 1 mapping may rewrite, and writes them back when the switch fails.
 */
class DockerProjectsRootIgnoredContractTest {

    @Test
    void scriptsAcceptLegacyParametersWithAWarningAndForwardNoRoot() throws IOException {
        Path root = repoRoot();
        String switcher = text(root.resolve("scripts/install/switch-mcp-backend.ps1"));
        String zip = text(root.resolve("scripts/install/install-windows.ps1"));

        for (String script : List.of(switcher, zip)) {
            assertTrue(script.contains("[string] $ProjectsRoot = ''"), "-ProjectsRoot stays accepted");
            assertTrue(script.contains("[switch] $ReplaceDockerProjectsRoot"), "-ReplaceDockerProjectsRoot stays accepted");
            assertFalse(script.contains("Get-UnsafeDockerProjectsRootReason"), "no projects root is checked any more");
            assertFalse(script.contains("Get-RecordedDockerProjectsRoot"), "no projects root is compared any more");
            assertFalse(script.contains("-ProjectsRoot is required"), "-ProjectsRoot is no longer required");
            assertFalse(script.contains("/REPLACEDOCKERPROJECTSROOT=1"), "no replacement is asked for");
            assertTrue(script.contains("The -ProjectsRoot parameter is ignored"), "-ProjectsRoot is reported");
            assertTrue(script.contains("The -ReplaceDockerProjectsRoot switch is ignored"),
                    "-ReplaceDockerProjectsRoot is reported");
            assertTrue(script.chars().allMatch(c -> c < 128), "shipped PowerShell scripts stay ASCII");
        }

        assertFalse(zip.contains("$SwitchParameters['ProjectsRoot']"), "the ZIP install forwards no root");
        assertFalse(zip.contains("$SwitchParameters['ReplaceDockerProjectsRoot']"), "nor a replacement");
        assertTrue(zip.indexOf("The -ProjectsRoot parameter is ignored") < zip.indexOf("Expand-Archive"),
                "the ZIP install warns before extracting the package");

        assertTrue(switcher.contains("Write-SwitchLog (\"WARNING ignored=ProjectsRoot "), "the switch log keeps the warning");
        assertTrue(switcher.contains("Write-SwitchLog (\"WARNING ignored=ReplaceDockerProjectsRoot "));
        String parameters = between(switcher, "$DockerParameters = @{", "}");
        assertFalse(parameters.contains("ProjectsRoot"), "the switcher forwards no root to the configurator");
        assertFalse(switcher.contains("$DockerParameters['ReplaceProjectsRoot']"), "nor a replacement");
        assertFalse(switcher.contains("projectsRoot=replace"));
        assertFalse(switcher.contains("$MarkerProjects"), "reusing the runtime compares no projects root");
        assertFalse(switcher.contains("projectsRoot = $ProjectsRoot"), "the switch state records no projects root");
    }

    @Test
    void everyDockerInstallSavesAndRestoresTheProjectMapping() throws IOException {
        String switcher = text(repoRoot().resolve("scripts/install/switch-mcp-backend.ps1"));

        String install = between(switcher, "$DockerParameters = @{", "& $DockerConfiguratorPath @DockerParameters");
        assertTrue(install.contains("$ProjectRootSnapshot = New-ProjectRootSnapshot"),
                "the mapping and the registry are saved before every Docker install or upgrade");
        assertFalse(switcher.contains("$ReplaceProjectsRoot"), "the snapshot depends on no projects root replacement");
        assertTrue(between(switcher, "catch {\n    $Failure = $_", "throw $Failure")
                .contains("Restore-ProjectRootSnapshot -Snapshot $ProjectRootSnapshot"),
                "a failed switch restores the mapping and the registry");
        assertTrue(switcher.contains("ROLLBACK projectMounts=restored result=success"));
    }

    @Test
    void installerAsksForNoRootAndNamesTheCommandThatExposesAProject() throws IOException {
        String installer = text(repoRoot().resolve("packaging/windows/minos-installer.iss.template"));

        assertFalse(installer.contains("Racine des projets"), "the Docker page asks for no projects root");
        assertFalse(installer.contains("-ProjectsRoot"), "the installer forwards no projects root");
        assertFalse(installer.contains("-ReplaceDockerProjectsRoot"), "nor a replacement");
        assertFalse(installer.contains("UnsafeDockerProjectsRootReason"));
        assertFalse(installer.contains("RecordedDockerProjectsRoot"));
        assertFalse(installer.contains("MB_YESNO) = IDYES then\n        ReplaceDockerProjectsRootConfirmed"));
        assertFalse(installer.contains("RegWriteStringValue(HKCU, InstallerRegistryPath(), 'ProjectsRoot'"));
        assertFalse(installer.contains("ReadInstallerValue('ProjectsRoot'"));
        assertTrue(installer.contains("DockerProjectsPage := CreateCustomPage("));
        assertTrue(installer.contains("prod-mcp-release.ps1 -Action AddProject -ProjectPath"),
                "the Docker page names the command that exposes a project");

        String legacy = between(installer, "procedure LogIgnoredDockerProjectsRootSettings;", "\nend;");
        assertTrue(legacy.contains("{param:DOCKERPROJECTSROOT|}"));
        assertTrue(legacy.contains("{param:REPLACEDOCKERPROJECTSROOT|}"));
        assertTrue(legacy.contains("RegQueryStringValue(HKCU, InstallerRegistryPath(), 'ProjectsRoot'"));
        assertTrue(legacy.contains("Log('Warning: /DOCKERPROJECTSROOT"), "the parameter reaches the setup log");
        assertTrue(installer.contains("RegDeleteValue(HKCU, InstallerRegistryPath(), 'ProjectsRoot');"),
                "the installation removes the registry value");
        String postInstall = between(installer, "procedure CurStepChanged(CurStep: TSetupStep);", "\nend;");
        int warning = postInstall.indexOf("LogIgnoredDockerProjectsRootSettings;");
        assertTrue(warning > 0 && warning < postInstall.indexOf("ConfigureSelectedMcpBackend()"),
                "the warnings are logged before the backend is configured");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void snapshotRestoresTheMappingByteForByteUnderWindowsPowerShell(@TempDir Path work) throws Exception {
        Path dataRoot = Files.createDirectories(work.resolve("docker-data"));
        Path mapping = Files.createDirectories(dataRoot.resolve("runtime")).resolve("project-paths.properties");
        Path entry = Files.createDirectories(dataRoot.resolve("registry/projects")).resolve("fixture.properties");
        byte[] format1 = "#MINOS project path mapping v1\r\nformatVersion=1\r\nhostRoot=C\\:/Users/alice\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] registered = "id=fixture\nrootRelativePath=app\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(mapping, format1);
        Files.write(entry, registered);

        run(work, dataRoot, "rewrite");
        assertArrayEquals(format1, Files.readAllBytes(mapping), "the format 1 mapping is restored byte for byte");
        assertArrayEquals(registered, Files.readAllBytes(entry), "the registry entry is restored byte for byte");

        Files.delete(mapping);
        run(work, dataRoot, "create");
        assertFalse(Files.exists(mapping), "a mapping created by the failed install is removed");
    }

    /** Saves with the real functions, extracted by the PowerShell parser, rewrites the files, then restores. */
    private static void run(Path work, Path dataRoot, String mode) throws Exception {
        Path harness = work.resolve("harness.ps1");
        Files.writeString(harness, """
                param([string] $ScriptPath, [string] $DockerDataRoot, [string] $Mode)
                $ErrorActionPreference = 'Stop'
                Set-StrictMode -Version Latest
                $tokens = $null; $errors = $null
                $ast = [System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref] $tokens, [ref] $errors)
                if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
                foreach ($name in @('New-ProjectRootSnapshot', 'Restore-ProjectRootSnapshot')) {
                    $isTarget = { param($n)
                        $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }
                    $found = @($ast.FindAll($isTarget, $true))
                    if ($found.Count -ne 1) { throw "expected one definition of $name, found $($found.Count)" }
                    . ([scriptblock]::Create($found[0].Extent.Text))
                }
                $mapping = Join-Path $DockerDataRoot 'runtime\\project-paths.properties'
                $snapshot = New-ProjectRootSnapshot
                [System.IO.File]::WriteAllText($mapping, "formatVersion=2`n")
                if ($Mode -eq 'rewrite') {
                    Get-ChildItem -LiteralPath (Join-Path $DockerDataRoot 'registry\\projects') -File |
                        ForEach-Object { [System.IO.File]::WriteAllText($_.FullName, 'rewritten') }
                }
                Restore-ProjectRootSnapshot -Snapshot $snapshot
                [Console]::Out.WriteLine('RESTORED')
                """, StandardCharsets.UTF_8);
        String output = WindowsPowerShellHarness.run(work, harness, "-ScriptPath",
                repoRoot().resolve("scripts/install/switch-mcp-backend.ps1").toString(),
                "-DockerDataRoot", dataRoot.toString(), "-Mode", mode);
        assertTrue(output.contains("RESTORED"), "harness output:\n" + output);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = from < 0 ? -1 : text.indexOf(end, from + start.length());
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
