package com.minos.intellij.protocol;

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

class MinosProcessSupervisorOrphanTest {

    @TempDir
    Path tmp;

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
    void normalRootExitStillCleansPreviouslyOwnedDetachedChildOnWindows() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        String command = "$p=Start-Process -PassThru ping -ArgumentList '-n','3600','127.0.0.1';"
                + "Set-Content -NoNewline -Path '" + pidFile.toString().replace("'", "''") + "' -Value $p.Id;"
                + "Start-Sleep -Milliseconds 1000";
        Process root = new ProcessBuilder(List.of(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", command)).start();
        awaitFile(pidFile);
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
        assertTrue(child.isAlive(), "fixture child must be alive before root exits");

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            assertTrue(supervisor.waitFor(15_000), "root should exit normally");
            supervisor.drainOutput();
        }

        awaitDead(child);
        assertFalse(child.isAlive(), "owned child must not survive a normal root exit");
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(25L);
        assertTrue(Files.exists(file), "timed out waiting for child pid file");
    }

    private static void awaitDead(ProcessHandle process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50L);
    }
}
