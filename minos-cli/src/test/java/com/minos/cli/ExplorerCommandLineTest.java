package com.minos.cli;

import com.minos.cli.command.FindSymbolCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minos explorer}: its options and exit codes, its help answered without opening a home, and its
 * routing to the registered entry point before any application is opened.
 */
class ExplorerCommandLineTest {

    @Test
    void serveIsHandedToTheRouteWithTheHomeAndAFreePortByDefault(@TempDir Path home) throws Exception {
        List<String> calls = new ArrayList<>();
        ExplorerLaunchRoute route = (routedHome, port, error) -> {
            calls.add(routedHome.getFileName() + ":" + port);
            return 7;
        };
        StringBuilder error = new StringBuilder();

        int byDefault = ExplorerCommandLine.run(new String[] {"explorer", "serve"}, home, error, () -> route);
        int chosen = ExplorerCommandLine.run(new String[] {"explorer", "serve", "--port", "8765"}, home, error, () -> route);
        int highest = ExplorerCommandLine.run(new String[] {"explorer", "serve", "--port", "65535"}, home, error, () -> route);

        assertEquals(List.of(7, 7, 7), List.of(byDefault, chosen, highest));
        assertEquals(List.of(home.getFileName() + ":0", home.getFileName() + ":8765", home.getFileName() + ":65535"), calls);
        assertEquals("", error.toString());
    }

    @Test
    void aUsageErrorNamesWhatToCorrectAndNeverResolvesTheRoute(@TempDir Path home) throws Exception {
        String[][] invalid = {
                {"explorer"}, {"explorer", "start"}, {"explorer", "serve", "--host", "0.0.0.0"},
                {"explorer", "serve", "--bind", "0.0.0.0"}, {"explorer", "serve", "--port"},
                {"explorer", "serve", "--port", "65536"}, {"explorer", "serve", "--port", "-1"},
                {"explorer", "serve", "--port", "http"}, {"explorer", "serve", "--port", "1", "--port", "2"},
                {"explorer", "serve", "8765"}, {"explorer", "serve", "--token", "x"}};
        for (String[] arguments : invalid) {
            StringBuilder error = new StringBuilder();

            int exit = ExplorerCommandLine.run(arguments, home, error, () -> {
                throw new AssertionError("the route is resolved on a usage error: " + String.join(" ", arguments));
            });

            assertEquals(FindSymbolCommand.USAGE_ERROR, exit, String.join(" ", arguments));
            assertTrue(error.toString().startsWith("error: "), error.toString());
            assertTrue(error.toString().contains("Usage: minos explorer serve [--port <n>]"), error.toString());
        }
    }

    @Test
    void theUsageOffersNoWayToListenElsewhereThanOnTheLoopback() {
        assertTrue(ExplorerCommandLine.USAGE.contains("loopback only (127.0.0.1)"));
        assertTrue(ExplorerCommandLine.USAGE.contains("never writes, indexes or synchronizes"));
        assertTrue(ExplorerCommandLine.USAGE.contains("team mode"));
        for (String option : List.of("--host", "--bind", "--address", "--token", "0.0.0.0")) {
            assertFalse(ExplorerCommandLine.USAGE.contains(option), option);
        }
        assertTrue(MinosCli.usage().contains("explorer serve     Serve the local read-only explorer"));
    }

    @Test
    void theHelpOfTheCommandIsAnsweredWithoutOpeningAHome(@TempDir Path root) {
        Path absentHome = root.resolve("absent").resolve("home");
        for (String[] arguments : new String[][] {{"explorer", "--help"}, {"explorer", "serve", "--help"}}) {
            Launch launch = launch(absentHome, arguments);

            assertEquals(FindSymbolCommand.SUCCESS, launch.exit(), launch.error());
            assertEquals(ExplorerCommandLine.USAGE, launch.output().strip().replace("\r\n", "\n"));
            assertEquals("", launch.error());
            assertFalse(Files.exists(absentHome), "the help must not create MINOS_HOME");
        }
    }

    @Test
    void withoutARegisteredEntryPointTheCommandFailsExplicitlyAndOpensNothing(@TempDir Path root) {
        Path absentHome = root.resolve("absent").resolve("home");

        Launch launch = launch(absentHome, "explorer", "serve");
        Launch usage = launch(absentHome, "explorer", "serve", "--port", "many");

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, launch.exit(), launch.error());
        assertEquals("error: MINOS bootstrap failed: " + ExplorerCommandLine.MISSING, launch.error().strip());
        assertFalse(launch.error().contains(root.toString()), launch.error());
        assertEquals(FindSymbolCommand.USAGE_ERROR, usage.exit());
        assertFalse(Files.exists(absentHome), "a missing entry point must not create MINOS_HOME");
    }

    private record Launch(int exit, String output, String error) {
    }

    /** Runs the launcher in this JVM; the application opener fails the test if the launcher ever calls it. */
    private static Launch launch(Path home, String... arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exit = MinosLauncher.launch(arguments,
                Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString()), new Properties(),
                new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(error, true, StandardCharsets.UTF_8),
                opened -> {
                    throw new IOException("the launcher opened an application for the explorer command");
                },
                (application, routed, out, err) -> {
                    throw new AssertionError("the explorer command reached the CLI runner");
                });
        return new Launch(exit, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }
}
