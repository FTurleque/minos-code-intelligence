package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosIdeHandshakeLauncherTest {

    @Test
    void processHandshakeDoesNotOpenOrCreateMinosHome(@TempDir Path root) throws Exception {
        Path absentHome = root.resolve("absent").resolve("home");
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        ProcessBuilder builder = new ProcessBuilder(List.of(
                javaExecutable.toString(),
                "-cp",
                testRuntimeClasspath(),
                MinosLauncher.class.getName(),
                "ide",
                "handshake",
                "--format",
                "json"
        ));
        builder.environment().put(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, absentHome.toString());

        Process process = builder.start();
        boolean completed = process.waitFor(20, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }

        assertTrue(completed, "MINOS IDE handshake child process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), error);
        assertTrue(output.contains("\"protocol\":\"minos-ide\""), output);
        assertTrue(output.contains("\"protocolVersion\":\"1\""), output);
        assertEquals("", error);
        assertFalse(Files.exists(absentHome), "handshake must not initialize MINOS_HOME");
    }

    private static String testRuntimeClasspath() {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("test runtime classpath is unavailable");
        }
        return classpath;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
