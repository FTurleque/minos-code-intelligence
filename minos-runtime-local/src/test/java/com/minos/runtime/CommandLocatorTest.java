package com.minos.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLocatorTest {

    @Test
    void pathResolutionIgnoresEmptyAndRelativeEntries(@TempDir Path temp) throws Exception {
        Path bin = Files.createDirectories(temp.resolve("trusted-bin")).toAbsolutePath().normalize();
        Path tool = Files.writeString(bin.resolve("minos-path-probe"), "fixture");
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Assumptions.assumeTrue(cwd.getRoot().equals(bin.getRoot()),
                "relative-path fixture requires temp and working directory on the same volume");
        Path relativeBin = cwd.relativize(bin);
        String separator = java.io.File.pathSeparator;

        assertTrue(CommandLocator.findInPath(
                tool.getFileName().toString(), separator + relativeBin + separator, false).isEmpty(),
                "empty/current-directory and relative PATH entries must never become launch authority");

        assertEquals(tool.toRealPath(), CommandLocator.findInPath(
                tool.getFileName().toString(), relativeBin + separator + bin, false).orElseThrow(),
                "the same executable must resolve once its directory is supplied as an absolute PATH entry");
    }

    @Test
    void linuxSecurityAuthorityCommandsUseRootOwnedSystemPathsInsteadOfPath() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) return;

        Path shell = CommandLocator.findSystemExecutable("sh").orElseThrow();

        assertEquals(shell, CommandLocator.find("sh").orElseThrow(),
                "security-authority commands must use the dedicated system resolver");
        assertTrue(shell.isAbsolute());
        assertTrue(Files.isRegularFile(shell));
        assertTrue(Files.isExecutable(shell));
        assertEquals(0L, ((Number) Files.getAttribute(shell, "unix:uid")).longValue(),
                "the selected security authority executable must be root-owned");
    }

    @Test
    void batchInvocationUsesCmdOuterQuotePairWithoutCallOrExpansion() {
        List<String> command = CommandLocator.windowsBatchInvocation(
                Path.of("C:\\Program Files\\MINOS & Tools\\provider.cmd"),
                "space value", "a&b", "x^y", "(z)", "bang!value", "é漢字");

        assertEquals("/d", command.get(1));
        assertEquals("/v:off", command.get(2));
        assertEquals("/s", command.get(3));
        assertEquals("/c", command.get(4));
        assertEquals(6, command.size());
        assertFalse(command.get(5).toLowerCase().contains("call "));
        assertTrue(command.get(5).startsWith("\"\"C:\\Program Files\\MINOS & Tools\\provider.cmd\""));
        assertTrue(command.get(5).endsWith("\"é漢字\"\""));
        assertTrue(command.get(5).contains("\"a&b\""));
        assertTrue(command.get(5).contains("\"x^y\""));
        assertTrue(command.get(5).contains("\"bang!value\""));
    }

    @Test
    void batchInvocationRejectsExpansionAndUnrepresentableTokens() {
        Path executable = Path.of("tool.cmd");
        assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(executable, "%PATH%"));
        assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(executable, "quote\"value"));
        assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(executable, "line\nvalue"));
    }

    @Test
    void realWindowsBatchInvocationUsesCanonicalSystem32Cmd() throws Exception {
        if (!CommandLocator.isWindows()) {
            return;
        }
        List<String> command = CommandLocator.windowsBatchInvocation(Path.of("C:\\fixture.cmd"));
        Path processor = Path.of(command.getFirst());
        Path expected = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toRealPath();
        assertTrue(processor.isAbsolute(), "cmd.exe must not be resolved from the current directory or PATH");
        assertTrue(Files.isRegularFile(processor), "resolved Windows command processor must exist");
        assertEquals(expected, processor.toRealPath(),
                "batch execution must be anchored to canonical SystemRoot\\System32\\cmd.exe, not ComSpec or PATH");
    }

    @Test
    void realWindowsPowerShellUsesCanonicalSystem32Host() throws Exception {
        if (!CommandLocator.isWindows()) return;
        Path actual = CommandLocator.windowsPowerShell().orElseThrow();
        Path expected = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                .toRealPath();
        assertEquals(expected, actual.toRealPath(),
                "the sandbox authority PowerShell host must not be selected from PATH");
    }

    @Test
    void realWindowsCmdPreservesAdversarialQuotedArguments() throws Exception {
        if (!CommandLocator.isWindows()) {
            return;
        }
        Path directory = Files.createTempDirectory("minos-cmd space & caret^ paren() bang! unicode-é-");
        Path script = directory.resolve("provider test.cmd");
        Path marker = directory.resolve("result marker.txt");
        Path unicodeCapture = directory.resolve("unicode capture.txt");
        Path captureHelper = directory.resolve("capture-unicode.ps1");
        Files.writeString(captureHelper, """
                param([string] $Value, [string] $Target)
                [System.IO.File]::WriteAllText(
                    $Target,
                    $Value,
                    [System.Text.UTF8Encoding]::new($false))
                """, StandardCharsets.US_ASCII);
        Files.writeString(script, """
                @echo off
                setlocal DisableDelayedExpansion
                if not "%~2"=="space value" exit /b 11
                if not "%~3"=="a&b" exit /b 12
                if not "%~4"=="x^y" exit /b 13
                if not "%~5"=="(z)" exit /b 14
                if not "%~6"=="bang!value" exit /b 15
                "%MINOS_TEST_POWERSHELL%" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~8" "%~7" "%~9"
                if errorlevel 1 exit /b 16
                > "%~1" echo PASS
                exit /b 0
                """, StandardCharsets.US_ASCII);

        Path windowsPowerShell = CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("Windows PowerShell 5.1 is unavailable"));
        ProcessBuilder builder = new ProcessBuilder(CommandLocator.invocation(
                script,
                marker.toString(),
                "space value",
                "a&b",
                "x^y",
                "(z)",
                "bang!value",
                "é漢字",
                captureHelper.toString(),
                unicodeCapture.toString()));
        builder.environment().put("MINOS_TEST_POWERSHELL", windowsPowerShell.toString());
        Process process = builder.start();

        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "cmd.exe qualification process timed out");
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), stderr);
        assertEquals("PASS", Files.readString(marker, StandardCharsets.UTF_8).trim());
        assertEquals("é漢字", Files.readString(unicodeCapture, StandardCharsets.UTF_8));
    }
}
