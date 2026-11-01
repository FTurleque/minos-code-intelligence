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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Docker projects root is bind-mounted read-only into the query and administration containers. The Windows
 * installer must not propose the user profile, and every entry point (wizard, switcher, ZIP install) must refuse a
 * root that would expose the profile, application data or MINOS's own folders.
 */
class DockerProjectsRootContractTest {

    private static final String FUNCTION = "Get-UnsafeDockerProjectsRootReason";
    private static final String CALL = "$UnsafeProjectsRoot = " + FUNCTION + " ";
    private static final String PROFILE = "C:\\Users\\alice";
    private static final String TEMPORARY = "C:\\Users\\alice\\AppData\\Local\\Temp\\";
    private static final String PROTECTED = String.join(";",
            "C:\\Users\\alice\\AppData\\Local", "C:\\Users\\alice\\AppData\\Roaming", "E:\\tools\\minos\\data",
            "C:\\Users\\alice\\AppData\\Local\\Temp\\qualification\\data");

    /** Roots every entry point must refuse: label -> projects root and user profile, separated by '|'. */
    private static final Map<String, String> REFUSED = cases(
            "profile", PROFILE + "|" + PROFILE,
            "profileOtherCase", "c:\\users\\ALICE\\|" + PROFILE,
            "profileParent", "C:\\Users|" + PROFILE,
            "volumeRoot", "C:\\|" + PROFILE,
            "otherVolumeRoot", "N:\\|" + PROFILE,
            "shareRoot", "\\\\server\\share|" + PROFILE,
            "localAppData", "C:\\Users\\alice\\AppData\\Local|" + PROFILE,
            "insideLocalAppData", "C:\\Users\\alice\\AppData\\Local\\MINOS\\projects|" + PROFILE,
            "roamingAppData", "C:\\Users\\alice\\AppData\\Roaming\\Code|" + PROFILE,
            "minosDataParent", "E:\\tools|" + PROFILE,
            "insideMinosData", "E:\\tools\\minos\\data\\projects|" + PROFILE,
            "unknownProfile", "N:\\workspace-dev|",
            "temporaryFolder", "C:\\Users\\alice\\AppData\\Local\\Temp|" + PROFILE,
            "insideTemporaryMinosData", "C:\\Users\\alice\\AppData\\Local\\Temp\\qualification\\data\\p|" + PROFILE,
            "temporaryMinosDataParent", "C:\\Users\\alice\\AppData\\Local\\Temp\\qualification|" + PROFILE);

    /** Roots dedicated to projects, which every entry point must accept. */
    private static final Map<String, String> ACCEPTED = cases(
            "profileSubfolder", "C:\\Users\\alice\\source\\repos|" + PROFILE,
            "otherVolumeFolder", "N:\\workspace-dev|" + PROFILE,
            "profileNamePrefix", "C:\\Users\\alicebis|" + PROFILE,
            "appDataNamePrefix", "C:\\Users\\alice\\AppDataX|" + PROFILE,
            "minosSibling", "E:\\tools\\minos\\projects|" + PROFILE,
            "temporarySubfolder", "C:\\Users\\alice\\AppData\\Local\\Temp\\qualification\\projects|" + PROFILE);

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
            int refusal = script.indexOf(CALL);
            int exists = script.indexOf("Test-Path -LiteralPath $ProjectsRoot -PathType Container");
            assertTrue(refusal > 0 && exists > refusal, "the unsafe-root refusal must precede the existence check");
            assertTrue(script.contains("[Environment]::GetFolderPath('UserProfile')"));
            assertTrue(script.contains("[Environment]::GetFolderPath('LocalApplicationData')"));
            assertTrue(script.contains("[Environment]::GetFolderPath('ApplicationData')"));
            assertTrue(script.chars().allMatch(c -> c < 128), "shipped PowerShell scripts stay ASCII");
        }
        assertTrue(zip.indexOf(CALL) < zip.indexOf("Expand-Archive"),
                "the ZIP install must refuse before extracting the package");
        assertTrue(switcher.indexOf(CALL) < switcher.indexOf("New-Item -ItemType Directory -Force -Path $RuntimeRoot"),
                "the switcher must refuse before writing its runtime folder");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusalUnderWindowsPowerShell(@TempDir Path work) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(REFUSED);
        all.putAll(ACCEPTED);

        Map<String, String> switcher = run(work, "scripts/install/switch-mcp-backend.ps1", all);
        Map<String, String> zip = run(work, "scripts/install/install-windows.ps1", all);
        assertEquals(switcher, zip, "the switcher and the ZIP install must decide alike");

        REFUSED.keySet().forEach(label -> assertRefusedWithoutPath(label, switcher.get(label)));
        ACCEPTED.keySet().forEach(label -> assertEquals("ACCEPTED", switcher.get(label), label + " must be accepted"));
    }

    private static void assertRefusedWithoutPath(String label, String reason) {
        assertFalse(reason.isEmpty() || reason.equals("ACCEPTED"), label + " must be refused");
        assertFalse(reason.contains("\\") || reason.contains(":"), label + " reason must carry no path");
    }

    /** Runs the real function, extracted by the PowerShell parser, on every case; returns label -> reason. */
    private static Map<String, String> run(Path work, String script, Map<String, String> cases) throws Exception {
        StringBuilder input = new StringBuilder();
        cases.forEach((label, value) -> input.append(label).append('|').append(value).append('\n'));
        Path casesFile = work.resolve("cases.txt");
        Files.writeString(casesFile, input.toString(), StandardCharsets.UTF_8);
        Path harness = work.resolve("harness.ps1");
        Files.writeString(harness, harnessText(), StandardCharsets.UTF_8);

        String output = WindowsPowerShellHarness.run(work, harness, "-ScriptPath", repoRoot().resolve(script).toString(),
                "-CasesPath", casesFile.toString(), "-Protected", PROTECTED, "-Temporary", TEMPORARY);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) reasons.put(line.substring(0, separator), line.substring(separator + 1).trim());
        }
        assertEquals(cases.keySet(), reasons.keySet(), script + " harness output:\n" + output);
        return reasons;
    }

    private static String harnessText() {
        return """
                param([string] $ScriptPath, [string] $CasesPath, [string] $Protected, [string] $Temporary)
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
                    $reason = %1$s -ProjectsRoot $parts[1] -UserProfile $parts[2] `
                        -ProtectedRoots ($Protected.Split(';')) -TemporaryRoot $Temporary
                    if ([string]::IsNullOrEmpty($reason)) { $reason = 'ACCEPTED' }
                    [Console]::Out.WriteLine($parts[0] + '=' + $reason)
                }
                """.formatted(FUNCTION);
    }

    private static Map<String, String> cases(String... labelsAndValues) {
        Map<String, String> cases = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) cases.put(labelsAndValues[i], labelsAndValues[i + 1]);
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
