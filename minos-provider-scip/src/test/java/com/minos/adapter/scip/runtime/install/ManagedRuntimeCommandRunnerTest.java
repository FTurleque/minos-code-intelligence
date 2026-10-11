package com.minos.adapter.scip.runtime.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded command runner of the three managers: output is captured to a log or a result, a failing command is
 * an error, and a command that outlives its timeout is killed and reported, never waited for. The commands are the
 * JVM running the test, so nothing depends on the tools of the host.
 */
class ManagedRuntimeCommandRunnerTest {

    /** The two runners that write a log file and throw on failure. */
    private enum LoggingRunner {
        PYTHON {
            @Override
            void run(List<String> command, Path directory, Path log, Duration timeout) throws Exception {
                ManagedScipPythonRuntimeManager.run(command, directory, log, timeout);
            }
        },
        PROVIDER {
            @Override
            void run(List<String> command, Path directory, Path log, Duration timeout) throws Exception {
                ManagedScipProviderRuntimeManager.run(command, directory, log, timeout);
            }
        };

        abstract void run(List<String> command, Path directory, Path log, Duration timeout) throws Exception;
    }

    @TempDir
    Path directory;

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", FakeTools.windows() ? "java.exe" : "java").toString();
    }

    /** A Java program that blocks on its standard input, which the runner never closes nor writes to. */
    private Path blockingProgram() throws IOException {
        Path source = directory.resolve("Blocker.java");
        Files.writeString(source, "public class Blocker { public static void main(String[] a) throws Exception {"
                + " System.out.println(\"started\"); System.in.read(); } }\n");
        return source;
    }

    private Path environmentProgram() throws IOException {
        Path source = directory.resolve("PrintEnvironment.java");
        Files.writeString(source, "public class PrintEnvironment { public static void main(String[] a) {"
                + " System.out.println(\"goproxy=\" + System.getenv(\"GOPROXY\")); } }\n");
        return source;
    }

    // ------------------------------------------------------------------ python and provider managers

    @ParameterizedTest
    @EnumSource(LoggingRunner.class)
    void aSuccessfulCommandWritesItsOutputToTheLogUnderAPrivateDirectory(LoggingRunner runner) throws Exception {
        Path log = directory.resolve("logs").resolve("tool.log");

        runner.run(List.of(java(), "-version"), directory, log, Duration.ofMinutes(2));

        assertTrue(Files.readString(log).contains("version"), "the command output is kept for the operator");
    }

    @ParameterizedTest
    @EnumSource(LoggingRunner.class)
    void aCommandThatExitsNonZeroFailsWithItsExitCode(LoggingRunner runner) {
        Path log = directory.resolve("logs").resolve("tool.log");

        List<String> command = List.of(java(), "--no-such-option");
        Duration timeout = Duration.ofMinutes(2);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runner.run(command, directory, log, timeout));

        assertTrue(failure.getMessage().startsWith("tool command failed with code 1; see "), failure.getMessage());
        assertTrue(Files.isRegularFile(log), "the log of the failed command remains readable");
    }

    @ParameterizedTest
    @EnumSource(LoggingRunner.class)
    void aCommandThatOutlivesItsTimeoutIsKilledAndReported(LoggingRunner runner) throws Exception {
        Path source = blockingProgram();
        Path log = directory.resolve("logs").resolve("tool.log");

        List<String> command = List.of(java(), source.toString());
        Duration timeout = Duration.ofSeconds(1);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runner.run(command, directory, log, timeout));

        assertTrue(failure.getMessage().startsWith("tool command timed out; see "), failure.getMessage());
    }

    @ParameterizedTest
    @EnumSource(LoggingRunner.class)
    void aCommandThatCannotBeStartedIsAnErrorNotASilentSuccess(LoggingRunner runner) {
        Path log = directory.resolve("logs").resolve("tool.log");
        List<String> missing = List.of(directory.resolve("no-such-tool").toString());

        assertThrows(IOException.class, () -> runner.run(missing, directory, log, Duration.ofSeconds(30)));
    }

    // ------------------------------------------------------------------ polyglot manager

    @Test
    void thePolyglotRunnerReturnsTheOutputAndLeavesNoTemporaryFile() throws Exception {
        ManagedPolyglotScipRuntimeManager.CommandResult result = ManagedPolyglotScipRuntimeManager.run(
                List.of(java(), "-version"), directory, Duration.ofMinutes(2), Map.of());

        assertTrue(result.success());
        assertTrue(result.output().contains("version"), result.output());
        try (Stream<Path> remaining = Files.list(directory)) {
            assertEquals(List.of(), remaining.toList(), "the capture file must be deleted");
        }
    }

    @Test
    void thePolyglotRunnerReportsAFailingCommandWithoutThrowing() throws Exception {
        ManagedPolyglotScipRuntimeManager.CommandResult result = ManagedPolyglotScipRuntimeManager.run(
                List.of(java(), "--no-such-option"), directory, Duration.ofMinutes(2), Map.of());

        assertFalse(result.success());
        assertFalse(result.output().isBlank(), "the reason of the failure is reported");
    }

    @Test
    void thePolyglotRunnerKillsACommandThatOutlivesItsTimeout() throws Exception {
        Path source = blockingProgram();

        ManagedPolyglotScipRuntimeManager.CommandResult result = ManagedPolyglotScipRuntimeManager.run(
                List.of(java(), source.toString()), directory, Duration.ofSeconds(1), Map.of());

        assertFalse(result.success());
        assertTrue(result.output().startsWith("command timed out after PT1S"), result.output());
    }

    @Test
    void thePolyglotRunnerPassesOnlyTheExplicitOverrides() throws Exception {
        Path source = environmentProgram();

        ManagedPolyglotScipRuntimeManager.CommandResult result = ManagedPolyglotScipRuntimeManager.run(
                List.of(java(), source.toString()), directory, Duration.ofMinutes(2),
                Map.of("GOPROXY", "https://proxy.golang.org"));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("goproxy=https://proxy.golang.org"), result.output());
    }

    @Test
    void thePolyglotRunnerRefusesMissingOverrides() {
        List<String> command = List.of(java(), "-version");
        Duration timeout = Duration.ofMinutes(2);

        assertThrows(NullPointerException.class,
                () -> ManagedPolyglotScipRuntimeManager.run(command, directory, timeout, null));
        try (Stream<Path> remaining = Files.list(directory)) {
            assertEquals(List.of(), remaining.toList(), "a refused call must not leave its capture file");
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
