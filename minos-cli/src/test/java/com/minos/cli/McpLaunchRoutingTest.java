package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 / ADR 0044 — {@code minos mcp} passe par le point d'entrée enregistré ({@link McpLaunchRoute}),
 * avant toute ouverture de {@code MinosApplication} : le home MINOS n'est pas créé par le lanceur. Sans
 * point d'entrée, le lanceur échoue explicitement, sans chemin, avec le code d'erreur d'exécution.
 */
class McpLaunchRoutingTest {

    private static final String ROUTED = "routed-to-registered-mcp-entry-point";

    @Test
    void mcpIsRoutedToTheRegisteredEntryPointBeforeMinosApplicationOpens(@TempDir Path root) throws Exception {
        Path services = Files.createDirectories(root.resolve("extra").resolve("META-INF").resolve("services"));
        Files.writeString(services.resolve(McpLaunchRoute.class.getName()),
                RecordingRoute.class.getName() + "\n", StandardCharsets.UTF_8);
        Path absentHome = root.resolve("absent").resolve("home");

        Result result = launch(testRuntimeClasspath() + File.pathSeparator + root.resolve("extra"), absentHome, "mcp");

        assertEquals(RecordingRoute.EXIT_CODE, result.exitCode(), result.error());
        assertEquals(ROUTED + " home", result.output().strip());
        assertEquals("", result.error());
        assertFalse(Files.exists(absentHome), "mcp must be routed before MinosApplication.open creates MINOS_HOME");
    }

    @Test
    void mcpWithoutAnyEntryPointFailsExplicitlyWithoutPath(@TempDir Path root) throws Exception {
        Path absentHome = root.resolve("absent").resolve("home");

        Result result = launch(testRuntimeClasspath(), absentHome, "mcp");

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, result.exitCode(), result.error());
        assertEquals("error: MINOS bootstrap failed: " + McpLaunchRoutes.MISSING, result.error().strip());
        assertFalse(result.error().contains(root.toString()), result.error());
        assertFalse(Files.exists(absentHome), "a missing MCP entry point must not create MINOS_HOME");
    }

    private static Result launch(String classpath, Path home, String... arguments) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new java.util.ArrayList<>(List.of(
                javaExecutable.toString(), "-cp", classpath, MinosLauncher.class.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString());
        Process process = builder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "MINOS child process timed out");
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
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

    private record Result(int exitCode, String output, String error) { }

    /** Point d'entrée de test : trace l'appel et rend un code reconnaissable, sans rien ouvrir. */
    public static final class RecordingRoute implements McpLaunchRoute {
        static final int EXIT_CODE = 7;

        @Override
        public int run(Path home) {
            System.out.println(ROUTED + " " + home.getFileName());
            return EXIT_CODE;
        }
    }
}
