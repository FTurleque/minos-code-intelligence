package com.minos.intellij.protocol;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosStrongProcessLauncherTest {

    @TempDir
    Path temp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void immediateDetachedChildCannotEscapeWindowsJobObjectOnStop() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        String escaped = pidFile.toString().replace("'", "''");
        String command = "$p=Start-Process -PassThru ping -ArgumentList '-n','3600','127.0.0.1';"
                + "Set-Content -NoNewline -LiteralPath '" + escaped + "' -Value $p.Id;"
                + "Start-Sleep -Seconds 5";
        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command));
        builder.directory(temp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(
                builder, temp.resolve("home").toString());
        long pid;
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            awaitFile(pidFile);
            pid = Long.parseLong(Files.readString(pidFile).trim());
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "fixture child must be alive before strong-boundary termination");
            supervisor.stop(null);
        }

        awaitDead(pid);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "child detached before any ProcessHandle poll must still be killed by the Job Object");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void immediateSetsidChildCannotEscapeQualifiedLinuxScopeOnStop() throws Exception {
        Assumptions.assumeTrue(MinosStrongProcessLauncher.linuxOwnershipAvailableForTests(),
                "runner has no qualified systemd user scope");
        Path pidFile = temp.resolve("child.pid");
        Path script = temp.resolve("detach.sh");
        String escaped = pidFile.toString().replace("'", "'\\''");
        Files.writeString(script, "#!/bin/sh\n"
                + "setsid sh -c 'sleep 300' >/dev/null 2>&1 &\n"
                + "printf '%s' \"$!\" > '" + escaped + "'\n"
                + "sleep 5\n");
        script.toFile().setExecutable(true);
        ProcessBuilder builder = new ProcessBuilder(script.toString());
        builder.directory(temp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(
                builder, temp.resolve("home").toString());
        long pid;
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            awaitFile(pidFile);
            pid = Long.parseLong(Files.readString(pidFile).trim());
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "fixture setsid child must be alive before strong-boundary termination");
            supervisor.stop(null);
        }

        awaitDead(pid);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "setsid child must remain owned by the systemd cgroup scope");
    }

    private static Path powershell() {
        return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20L);
        assertTrue(Files.exists(file), "timed out waiting for detached child PID");
    }

    private static void awaitDead(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
    }
}
