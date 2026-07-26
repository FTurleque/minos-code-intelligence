package com.minos.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadedJarSmokeIT {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void shadedJarStartsWithTheBuildJavaRuntime(@TempDir Path temporaryDirectory) throws Exception {
        Path shadedJar = Path.of(System.getProperty("minos.shaded.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(shadedJar), () -> "missing shaded JAR: " + shadedJar);

        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dminos.home=" + temporaryDirectory.resolve("minos-home"),
                "-jar",
                shadedJar.toString(),
                "nexus-export",
                "--help")
                .redirectErrorStream(true)
                .start();

        boolean completed = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(completed, () -> "shaded JAR timed out after " + PROCESS_TIMEOUT + ":\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("nexus-export"), output);
        assertTrue(output.contains("--root"), output);
    }
}
