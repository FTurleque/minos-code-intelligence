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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Docker projects root is bind-mounted read-only into the query and administration containers. The Windows
 * installer must not propose the user profile, and every entry point (wizard, switcher, ZIP install) must refuse a
 * root that would expose the profile, application data or MINOS's own folders.
 */
class DockerProjectsRootContractTest {

    private static final String FUNCTION = "Get-UnsafeDockerProjectsRootReason";
    private static final String PROFILE = "C:\\Users\\alice";
    private static final String PROTECTED = String.join(";",
            "C:\\Users\\alice\\AppData\\Local", "C:\\Users\\alice\\AppData\\Roaming", "E:\\tools\\minos\\data");
    private static final String TEMPORARY = "C:\\Users\\alice\\AppData\\Local\\Temp\\";

    @Test
    void installerProposesNoDefaultAndRefusesUnsafeRoots() throws IOException {
        String installer = text(repoRoot().resolve("packaging/windows/minos-installer.iss.template"));

        assertFalse(installer.contains("ReadInstallerValue('ProjectsRoot', ExpandConstant('{%USERPROFILE}'))"),
                "the installer must not propose the user profile as the Docker projects root");
        assertTrue(installer.contains("ReadInstallerValue('ProjectsRoot', '')"),
                "without a saved value the Docker projects root field must start empty");
        assertTrue(installer.contains("function UnsafeDockerProjectsRootReason(Path: String): String;"));

        String nextButton = between(installer, "function NextButtonClick(CurPageID: Integer): Boolean;",
                "procedure PromptForUserDataRemoval;");
        String dockerPage = nextButton.substring(nextButton.indexOf("if (CurPageID = DockerProjectsPage.ID)"));
        int empty = dockerPage.indexOf("if Trim(DockerProjectsPage.Values[0]) = '' then");
        int unsafe = dockerPage.indexOf("Reason := UnsafeDockerProjectsRootReason(DockerProjectsPage.Values[0]);");
        int exists = dockerPage.indexOf("if not DirExists(DockerProjectsPage.Values[0]) then");
        assertTrue(empty >= 0 && unsafe > empty && exists > unsafe,
                "the Docker page must refuse an empty, then an unsafe root, before checking that it exists");
    }

    @Test
    void scriptsRefuseUnsafeRootsBeforeTouchingAnything() throws IOException {
        Path root = repoRoot();
        String switcher = text(root.resolve("scripts/install/switch-mcp-backend.ps1"));
        String zip = text(root.resolve("scripts/install/install-windows.ps1"));

        for (String script : List.of(switcher, zip)) {
            assertTrue(script.contains("function " + FUNCTION + "("));
            int refusal = script.indexOf("$UnsafeProjectsRoot = " + FUNCTION + " ");
            int exists = script.indexOf("Test-Path -LiteralPath $ProjectsRoot -PathType Container");
            assertTrue(refusal > 0 && exists > refusal, "the unsafe-root refusal must precede the existence check");
            assertTrue(script.contains("[Environment]::GetFolderPath('UserProfile')"));
            assertTrue(script.contains("[Environment]::GetFolderPath('LocalApplicationData')"));
            assertTrue(script.contains("[Environment]::GetFolderPath('ApplicationData')"));
        }
        assertTrue(zip.indexOf("$UnsafeProjectsRoot = " + FUNCTION + " ") < zip.indexOf("Expand-Archive"),
                "the ZIP install must refuse before extracting the package");
        assertTrue(switcher.indexOf("$UnsafeProjectsRoot = " + FUNCTION + " ")
                        < switcher.indexOf("New-Item -ItemType Directory -Force -Path $RuntimeRoot"),
                "the switcher must refuse before writing its runtime folder");
        for (String script : List.of(switcher, zip)) {
            assertTrue(script.chars().allMatch(c -> c < 128), "shipped PowerShell scripts stay ASCII");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusalUnderWindowsPowerShell(@TempDir Path work) throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("profile", PROFILE + "|" + PROFILE);
        cases.put("profileOtherCase", "c:\\users\\ALICE\\|" + PROFILE);
        cases.put("profileParent", "C:\\Users|" + PROFILE);
        cases.put("volumeRoot", "C:\\|" + PROFILE);
        cases.put("otherVolumeRoot", "N:\\|" + PROFILE);
        cases.put("shareRoot", "\\\\server\\share|" + PROFILE);
        cases.put("localAppData", "C:\\Users\\alice\\AppData\\Local|" + PROFILE);
        cases.put("insideLocalAppData", "C:\\Users\\alice\\AppData\\Local\\MINOS\\projects|" + PROFILE);
        cases.put("roamingAppData", "C:\\Users\\alice\\AppData\\Roaming\\Code|" + PROFILE);
        cases.put("minosDataParent", "E:\\tools|" + PROFILE);
        cases.put("insideMinosData", "E:\\tools\\minos\\data\\projects|" + PROFILE);
        cases.put("unknownProfile", "N:\\workspace-dev|");
        cases.put("profileSubfolder", "C:\\Users\\alice\\source\\repos|" + PROFILE);
        cases.put("otherVolumeFolder", "N:\\workspace-dev|" + PROFILE);
        cases.put("profileNamePrefix", "C:\\Users\\alicebis|" + PROFILE);
        cases.put("appDataNamePrefix", "C:\\Users\\alice\\AppDataX|" + PROFILE);
        cases.put("minosSibling", "E:\\tools\\minos\\projects|" + PROFILE);
        cases.put("temporaryFolder", "C:\\Users\\alice\\AppData\\Local\\Temp|" + PROFILE);
        cases.put("temporarySubfolder", "C:\\Users\\alice\\AppData\\Local\\Temp\\minos-q\\projects|" + PROFILE);

        Map<String, String> switcher = run(work, "scripts/install/switch-mcp-backend.ps1", cases);
        Map<String, String> zip = run(work, "scripts/install/install-windows.ps1", cases);
        assertEquals(switcher, zip, "the switcher and the ZIP install must decide alike");

        for (String refused : List.of("profile", "profileOtherCase", "profileParent", "volumeRoot",
                "otherVolumeRoot", "shareRoot", "localAppData", "insideLocalAppData", "roamingAppData",
                "minosDataParent", "insideMinosData", "unknownProfile", "temporaryFolder")) {
            String reason = switcher.get(refused);
            assertFalse(reason.isEmpty() || reason.equals("ACCEPTED"), refused + " must be refused");
            assertFalse(reason.contains("\\") || reason.contains(":"), refused + " reason must carry no path");
        }
        for (String accepted : List.of("profileSubfolder", "otherVolumeFolder", "profileNamePrefix",
                "appDataNamePrefix", "minosSibling", "temporarySubfolder")) {
            assertEquals("ACCEPTED", switcher.get(accepted), accepted + " must be accepted");
        }
    }

    /** Runs the real function, extracted by the PowerShell parser, on every case; returns label -> reason. */
    private static Map<String, String> run(Path work, String script, Map<String, String> cases) throws Exception {
        Path scriptPath = repoRoot().resolve(script);
        StringBuilder input = new StringBuilder();
        cases.forEach((label, value) -> input.append(label).append('|').append(value).append('\n'));
        Path casesFile = work.resolve("cases.txt");
        Files.writeString(casesFile, input.toString(), StandardCharsets.UTF_8);
        Path harness = work.resolve("harness.ps1");
        Files.writeString(harness, """
                param([string] $ScriptPath, [string] $CasesPath, [string] $Protected, [string] $Temporary)
                $ErrorActionPreference = 'Stop'
                $tokens = $null; $errors = $null
                $ast = [System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref] $tokens, [ref] $errors)
                if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
                $found = @($ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq '%s' }, $true))
                if ($found.Count -ne 1) { throw "expected one definition, found $($found.Count)" }
                . ([scriptblock]::Create($found[0].Extent.Text))
                foreach ($line in [System.IO.File]::ReadAllLines($CasesPath)) {
                    if ([string]::IsNullOrWhiteSpace($line)) { continue }
                    $parts = $line.Split('|')
                    $reason = %s -ProjectsRoot $parts[1] -UserProfile $parts[2] -ProtectedRoots ($Protected.Split(';')) -TemporaryRoot $Temporary
                    if ([string]::IsNullOrEmpty($reason)) { $reason = 'ACCEPTED' }
                    [Console]::Out.WriteLine($parts[0] + '=' + $reason)
                }
                """.formatted(FUNCTION, FUNCTION), StandardCharsets.UTF_8);

        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", harness.toString(),
                "-ScriptPath", scriptPath.toString(), "-CasesPath", casesFile.toString(), "-Protected", PROTECTED, "-Temporary", TEMPORARY);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = Map.copyOf(environment);
        environment.clear();
        for (String name : List.of("SystemRoot", "windir", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA",
                "APPDATA", "ComSpec")) {
            String value = inherited.get(name);
            if (value != null) environment.put(name, value);
        }
        builder.directory(work.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "PowerShell harness timed out");
        assertEquals(0, process.exitValue(), script + " harness failed:\n" + output);

        Map<String, String> reasons = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) reasons.put(line.substring(0, separator), line.substring(separator + 1).trim());
        }
        assertEquals(cases.keySet(), reasons.keySet(), script + " harness output:\n" + output);
        return reasons;
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
