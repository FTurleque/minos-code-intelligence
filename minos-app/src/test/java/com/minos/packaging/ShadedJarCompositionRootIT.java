package com.minos.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 / ADR 0042 — contrat de packaging : le JAR distribué (ombré) résout la racine de composition.
 *
 * <p>Il contient exactement une déclaration {@code MinosApplicationComposer} (fusionnée par le
 * {@code ServicesResourceTransformer}), sa classe, la découverte réelle y trouve une seule
 * implémentation, {@code MinosApplication.open} fonctionne en ligne de commande, et aucune
 * dépendance de test (Testcontainers, support PostgreSQL de test) n'y entre.</p>
 */
class ShadedJarCompositionRootIT {

    private static final String COMPOSER_SERVICE = "META-INF/services/com.minos.application.MinosApplicationComposer";
    private static final String BACKEND_SERVICE = "META-INF/services/com.minos.storage.StorageBackendProvider";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void shadedJarDeclaresExactlyOneCompositionRootAndNoTestDependency() throws IOException {
        try (JarFile jar = new JarFile(shadedJar().toFile())) {
            assertEquals(List.of("com.minos.bootstrap.DefaultMinosApplicationComposer"), serviceLines(jar, COMPOSER_SERVICE));
            assertNotNull(jar.getJarEntry("com/minos/bootstrap/DefaultMinosApplicationComposer.class"));
            assertNotNull(jar.getJarEntry("com/minos/application/MinosApplicationComposers.class"));
            assertEquals(List.of("com.minos.storage.postgresql.PostgresStorageBackendProvider"),
                    serviceLines(jar, BACKEND_SERVICE));
            List<String> leaked = jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.startsWith("org/testcontainers/")
                            || name.equals("com/minos/storage/postgresql/PostgresTestSupport.class")
                            || name.startsWith("com/minos/characterization/"))
                    .limit(5)
                    .toList();
            assertEquals(List.of(), leaked, "test-only dependencies must not reach the distribution");
        }
    }

    @Test
    void realDiscoveryInsideTheShadedJarFindsTheSingleCompositionRoot() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{shadedJar().toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> composers = Class.forName("com.minos.application.MinosApplicationComposers", true, loader);
            Object composer = composers.getMethod("resolve").invoke(null);
            assertEquals("com.minos.bootstrap.DefaultMinosApplicationComposer", composer.getClass().getName());
            assertEquals(loader, composer.getClass().getClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void shadedJarOpensTheApplicationThroughTheCompositionRoot(@TempDir Path temporaryDirectory) throws Exception {
        Path home = temporaryDirectory.resolve("minos-home");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dminos.home=" + home,
                "-jar",
                shadedJar().toString(),
                "project", "list", "--format", "json")
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(completed, () -> "shaded JAR timed out after " + PROCESS_TIMEOUT + ":\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertEquals("{\"count\":0,\"projects\":[]}", output.strip());
        assertFalse(output.contains("composition root"), output);
        assertTrue(Files.isDirectory(home.resolve("registry")), "the composed local backend must have opened the home");
    }

    private static Path shadedJar() {
        Path shadedJar = Path.of(System.getProperty("minos.shaded.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(shadedJar), () -> "missing shaded JAR: " + shadedJar);
        return shadedJar;
    }

    private static List<String> serviceLines(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, () -> "missing service declaration " + name);
        try (InputStream input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }
}
