package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C04 : le plugin lance par défaut {@code minos.cmd} sous Windows. Ces tests démarrent un vrai
 * {@code .cmd} à travers la chaîne complète (construction de la ligne de commande, lanceur de propriété, Job Object,
 * supervision) : c'est la seule preuve que le lanceur par lots démarre et reçoit ses arguments intacts.
 */
@EnabledOnOs(OS.WINDOWS)
class MinosCommandLineBatchLaunchTest {
    private static final String WINDOWS = "Windows 11";

    @TempDir
    Path temp;

    @Test
    void aBatchLauncherStartsAndReceivesItsArgumentsIntact() throws Exception {
        Path launcher = writeBatch("echo-arguments.cmd", String.join("\r\n",
                "@echo off",
                "setlocal EnableDelayedExpansion",
                "set \"A1=%~1\"",
                "set \"A2=%~2\"",
                "set \"A3=%~3\"",
                "echo [!A1!]",
                "echo [!A2!]",
                "echo [!A3!]",
                "exit /b 0",
                ""));

        Run run = run(launcher, List.of("simple", "a&b", "x y|<>^"));

        assertEquals(0, run.exitValue(), run.describe());
        assertEquals(List.of("[simple]", "[a&b]", "[x y|<>^]"), run.stdoutLines(), run.describe());
    }

    @Test
    void theExitCodeOfTheBatchLauncherIsReported() throws Exception {
        Path launcher = writeBatch("exit-three.cmd", "@echo off\r\nexit /b 3\r\n");

        Run run = run(launcher, List.of("any"));

        assertEquals(3, run.exitValue(), run.describe());
    }

    @Test
    void stoppingABatchLaunchKillsTheLauncherAndItsChildAndLeavesNoOwnershipPlan() throws Exception {
        Path launcher = writeBatch("long-running.cmd", String.join("\r\n",
                "@echo off",
                "\"" + powershell() + "\" -NoLogo -NoProfile -NonInteractive -Command "
                        + "\"[System.IO.File]::WriteAllText('%~dp0child.pid', [string]$PID); Start-Sleep -Seconds 120\"",
                ""));
        Path childPid = launcher.resolveSibling("child.pid");
        Path home = temp.resolve("minos-home");
        ProcessBuilder builder = new ProcessBuilder(MinosCommandLine.build(launcher.toString(), List.of(), WINDOWS));
        builder.directory(temp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(builder, home.toString());
        long childProcessId;
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            assertTrue(awaitFile(childPid, 30), "the batch launcher never started its child: " + supervisor.stderr());
            childProcessId = Long.parseLong(Files.readString(childPid, StandardCharsets.US_ASCII).trim());
            assertTrue(ProcessHandle.of(childProcessId).map(ProcessHandle::isAlive).orElse(false),
                    "the child must be alive before the stop");

            supervisor.stop(null);
        }

        assertFalse(launch.process().isAlive(), "the batch launcher process must be dead");
        awaitExit(childProcessId, 10);
        assertFalse(isAlive(childProcessId), "the child of the batch launcher must be dead");
        assertEquals(List.of(), plans(home.resolve("intellij/process-ownership")),
                "no ownership plan may remain after the launch");
    }

    /** Absolute path: the fixture must not depend on the PATH of the process the launcher starts. */
    private static Path powershell() {
        return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    private Path writeBatch(String name, String content) throws IOException {
        Path directory = Files.createDirectories(temp.resolve("with space"));
        return Files.writeString(directory.resolve(name), content, StandardCharsets.US_ASCII);
    }

    private Run run(Path launcher, List<String> arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(MinosCommandLine.build(launcher.toString(), arguments, WINDOWS));
        builder.directory(temp.toFile());
        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(
                builder, temp.resolve("minos-home").toString());
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            assertTrue(supervisor.waitFor(TimeUnit.SECONDS.toMillis(60)), "the batch launch timed out");
            supervisor.drainOutput();
            return new Run(supervisor.exitValue(), supervisor.stdout(), supervisor.stderr());
        }
    }

    /** Attend la creation du fichier par une surveillance du dossier, sans dormir : faux si le delai est depasse. */
    private static boolean awaitFile(Path file, long timeoutSeconds) throws IOException, InterruptedException {
        try (WatchService watcher = file.getFileSystem().newWatchService()) {
            file.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!Files.isRegularFile(file)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key != null) key.reset();
            }
            return true;
        }
    }

    /** Attend la fin du processus par son futur de sortie : au-dela du delai, l'assertion de l'appelant le signale. */
    private static void awaitExit(long processId, long timeoutSeconds) throws InterruptedException {
        Optional<ProcessHandle> handle = ProcessHandle.of(processId);
        if (handle.isEmpty()) return;
        try {
            handle.get().onExit().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException stillRunning) {
            // the caller asserts that the process is gone and reports it otherwise
        }
    }

    private static boolean isAlive(long processId) {
        Optional<ProcessHandle> handle = ProcessHandle.of(processId);
        return handle.map(ProcessHandle::isAlive).orElse(false);
    }

    private static List<String> plans(Path ownership) throws IOException {
        if (!Files.isDirectory(ownership)) return List.of();
        try (Stream<Path> entries = Files.list(ownership)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> name.startsWith("cli-") && name.endsWith(".plan"))
                    .sorted().toList();
        }
    }

    private record Run(int exitValue, String stdout, String stderr) {
        List<String> stdoutLines() {
            return stdout.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        }

        String describe() {
            return "exit=" + exitValue + " stdout=" + stdout + " stderr=" + stderr;
        }
    }
}
