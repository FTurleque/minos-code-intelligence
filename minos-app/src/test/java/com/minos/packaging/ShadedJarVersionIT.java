package com.minos.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 / ADR 0044 — le JAR distribué annonce la version de son manifeste : {@code --version} affiche
 * exactement {@code MINOS <Implementation-Version>}, quel que soit le module qui porte
 * {@code MinosVersion}.
 */
class ShadedJarVersionIT {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void versionOptionPrintsTheManifestImplementationVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path shadedJar = Path.of(System.getProperty("minos.shaded.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(shadedJar), () -> "missing shaded JAR: " + shadedJar);
        String manifestVersion;
        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            manifestVersion = jar.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        }
        assertNotNull(manifestVersion, "the shaded JAR manifest must carry Implementation-Version");

        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dminos.home=" + temporaryDirectory.resolve("minos-home"),
                "-jar",
                shadedJar.toString(),
                "--version")
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(completed, () -> "shaded JAR timed out after " + PROCESS_TIMEOUT + ":\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertEquals("MINOS " + manifestVersion, output.strip());
    }
}
