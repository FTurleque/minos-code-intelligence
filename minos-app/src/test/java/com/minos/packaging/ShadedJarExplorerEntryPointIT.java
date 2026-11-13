package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaging contract of {@code minos explorer serve}: the distributed JAR declares exactly one
 * {@code com.minos.cli.ExplorerLaunchRoute}, holds the surface and its embedded page, and the launcher of
 * that JAR answers the command.
 */
class ShadedJarExplorerEntryPointIT {

    private static final String ROUTE_SERVICE = "META-INF/services/com.minos.cli.ExplorerLaunchRoute";
    private static final String PROVIDER = "com.minos.app.ExplorerLaunchRouteProvider";

    @Test
    void theShadedJarDeclaresExactlyOneExplorerEntryPointAndHoldsTheSurface() throws IOException {
        try (JarFile jar = new JarFile(shadedJar().toFile())) {
            assertEquals(List.of(PROVIDER), serviceLines(jar, ROUTE_SERVICE));
            for (String entry : List.of("com/minos/app/ExplorerLaunchRouteProvider.class",
                    "com/minos/cli/ExplorerLaunchRoute.class", "com/minos/explorer/ExplorerSession.class",
                    "com/minos/explorer/web/index.html", "com/minos/explorer/web/app.js",
                    "com/minos/explorer/web/app.css")) {
                assertNotNull(jar.getJarEntry(entry), () -> "missing from the shaded JAR: " + entry);
            }
        }
    }

    @Test
    void theLauncherOfTheShadedJarAnswersTheCommandAndRefusesAnotherListeningAddress() throws Exception {
        Result help = launch("explorer", "--help");
        Result widened = launch("explorer", "serve", "--host", "0.0.0.0");

        assertEquals(0, help.exitCode(), help.error());
        assertTrue(help.output().contains("Usage: minos explorer serve [--port <n>]"), help.output());
        assertTrue(help.output().contains("loopback only (127.0.0.1)"), help.output());
        assertEquals(2, widened.exitCode(), widened.error());
        assertTrue(widened.error().contains("unknown explorer serve option: --host"), widened.error());
        assertFalse(widened.error().contains("http://"), "nothing listens after a usage error");
    }

    private static Result launch(String... arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new java.util.ArrayList<>(List.of(java.toString(), "-jar", shadedJar().toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        Path home = Files.createTempDirectory(Path.of("target"), "explorer-it-").toAbsolutePath().resolve("absent-home");
        builder.environment().put("MINOS_HOME", home.toString());
        Process process = builder.start();
        boolean completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertTrue(completed, "the MINOS child process timed out");
        assertFalse(Files.exists(home), "a help request or a usage error must not create MINOS_HOME");
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output, String error) {
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
