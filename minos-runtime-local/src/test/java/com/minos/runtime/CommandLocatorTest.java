package com.minos.runtime;

import org.junit.jupiter.api.Test;

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
    void realWindowsCmdPreservesAdversarialQuotedArguments() throws Exception {
        if (!CommandLocator.isWindows()) {
            return;
        }
        Path directory = Files.createTempDirectory("minos-cmd space & caret^ paren() bang! unicode-é-");
        Path script = directory.resolve("provider test.cmd");
        Path marker = directory.resolve("result marker.txt");
        Files.writeString(script, """
                @echo off
                setlocal DisableDelayedExpansion
                if not "%~2"=="space value" exit /b 11
                if not "%~3"=="a&b" exit /b 12
                if not "%~4"=="x^y" exit /b 13
                if not "%~5"=="(z)" exit /b 14
                if not "%~6"=="bang!value" exit /b 15
                if not "%~7"=="é漢字" exit /b 16
                > "%~1" echo PASS
                exit /b 0
                """, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(CommandLocator.invocation(
                script,
                marker.toString(),
                "space value",
                "a&b",
                "x^y",
                "(z)",
                "bang!value",
                "é漢字")).start();

        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "cmd.exe qualification process timed out");
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), stderr);
        assertEquals("PASS", Files.readString(marker, StandardCharsets.UTF_8).trim());
    }
}
