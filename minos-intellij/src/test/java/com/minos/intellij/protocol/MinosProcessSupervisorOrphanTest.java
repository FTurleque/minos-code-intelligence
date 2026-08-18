package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosProcessSupervisorOrphanTest {

    private static final long WINDOWS_LAUNCH_TIMEOUT_SECONDS = 45L;

    @TempDir
    Path tmp;

    @Test
    void pidReacquisitionRequiresAnObservedStartInstant() {
        assertFalse(MinosProcessSupervisor.mayReacquireByPid(Optional.empty()),
                "a bare PID must never authorize termination of a re-acquired process");
        assertTrue(MinosProcessSupervisor.mayReacquireByPid(Optional.of(Instant.EPOCH)),
                "PID reacquisition is allowed only when a strong start-time identity can be verified");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void normalRootExitStillCleansPreviouslyOwnedDetachedChildOnUnix() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        Path script = tmp.resolve("orphan.sh");
        Files.writeString(script, """
                #!/bin/sh
                sleep 300 >/dev/null 2>&1 &
                child=$!
                printf '%s' "$child" > '%s'
                sleep 1
                exit 0
                """.formatted("%s", pidFile.toString()));
        script.toFile().setExecutable(true);

        Process root = new ProcessBuilder(script.toString()).start();
        awaitFile(pidFile);
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
        assertTrue(child.isAlive(), "fixture child must be alive before root exits");

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            assertTrue(supervisor.waitFor(10_000), "root should exit normally");
            supervisor.drainOutput();
        }

        awaitDead(child);
        assertFalse(child.isAlive(), "owned child must not survive a normal root exit");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void normalRootExitStillCleansDetachedChildThroughProductionJobBoundaryOnWindows() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        String command = "$p=Start-Process -PassThru ping -ArgumentList '-n','3600','127.0.0.1';"
                + "Set-Content -NoNewline -LiteralPath '" + pidFile.toString().replace("'", "''") + "' -Value $p.Id;"
                + "Start-Sleep -Milliseconds 500; exit 0";
        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command));
        builder.directory(tmp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(
                builder, tmp.resolve("home").toString());
        long childPid;
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            awaitWindowsFixtureStart(pidFile, launch, supervisor);
            childPid = Long.parseLong(Files.readString(pidFile).trim());
            assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "fixture child must be alive while the CLI root is still running");
            assertTrue(supervisor.waitFor(45_000), "Job Object launcher should finish after CLI root exit");
            supervisor.drainOutput();
        }

        awaitDead(childPid);
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "detached child must not survive normal production CLI completion");
    }

    private static Path powershell() {
        return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(25L);
        assertTrue(Files.exists(file), "timed out waiting for child pid file");
    }

    private static void awaitWindowsFixtureStart(
            Path file,
            MinosStrongProcessLauncher.Launch launch,
            MinosProcessSupervisor supervisor
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WINDOWS_LAUNCH_TIMEOUT_SECONDS);
        while (!Files.exists(file) && launch.process().isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
        if (Files.exists(file)) return;
        if (!launch.process().isAlive()) {
            supervisor.drainOutput();
            throw new AssertionError("Windows Job Object launcher exited before the fixture child published its PID"
                    + " (exit=" + supervisor.exitValue()
                    + ", stdout=" + diagnostic(supervisor.stdout())
                    + ", stderr=" + diagnostic(supervisor.stderr()) + ")");
        }
        throw new AssertionError("timed out after " + WINDOWS_LAUNCH_TIMEOUT_SECONDS
                + "s waiting for the fixture child PID while the Windows Job Object launcher remained alive");
    }

    private static String diagnostic(String value) {
        String flattened = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return flattened.length() <= 1_000 ? flattened : flattened.substring(0, 1_000) + "...";
    }

    private static void awaitDead(ProcessHandle process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50L);
    }

    private static void awaitDead(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
    }
}
