package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.cli.command.FindSymbolCommand;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An installation whose composition root does not offer the WSL2 route refuses {@code index --via-wsl} before any
 * work: an error naming the option, no run, no lease, and the MINOS_HOME tree is unchanged.
 */
class IndexViaWslSideEffectsTest {

    @TempDir Path temp;

    @Test
    void anInstallationWithoutTheRouteRefusesWithoutTouchingTheHome() throws Exception {
        Path home = temp.resolve("home");
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId>
                <version>1</version></project>
                """);
        Path source = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("Demo.java"), "package demo; public class Demo { }\n");

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject registered = application.projectRegistry().registerProject(project, "demo");
            application.projectRegistry().listProjects();
            Map<String, String> before = HomeTree.of(home);

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            int code = MinosCliRunner.run(application,
                    new String[]{"index", registered.id().toString(), "--via-wsl"}, output, error);

            // Off Windows the parse step refuses the option; on a Windows installation without the route, the backend
            // refuses before resolving the project. Either way the option is named and nothing is written.
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            int expected = windows ? FindSymbolCommand.EXECUTION_ERROR : FindSymbolCommand.USAGE_ERROR;
            assertEquals(expected, code, error.toString());
            assertTrue(error.toString().contains("--via-wsl"), error.toString());
            assertEquals("", output.toString());
            assertEquals(before, HomeTree.of(home), "a refused --via-wsl changed MINOS_HOME");
        }
    }
}
