package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the fallback termination policy shared by the process executor and the ownership tracker.
 * The kernel boundary remains the containment authority; these are the defence-in-depth invariants.
 */
class ProcessTreeTerminationTest {

    private static final Duration GRACEFUL = Duration.ofMillis(300);
    private static final Duration FORCED = Duration.ofSeconds(10);

    @Test
    void theHostJvmIsNeverDestroyed() {
        ProcessHandle host = ProcessHandle.current();
        assertTrue(ProcessTreeTermination.isHostProcess(host));

        // Both a graceful and a forced request must be refused outright.
        ProcessTreeTermination.destroyIfNotHost(host, false);
        ProcessTreeTermination.destroyIfNotHost(host, true);

        assertTrue(host.isAlive(), "the host JVM must survive every fallback termination request");
        assertTrue(ProcessHandle.current().isAlive());
    }

    @Test
    void aChildIsNotMistakenForTheHost() throws Exception {
        Process child = sleeper();
        try {
            assertFalse(ProcessTreeTermination.isHostProcess(child.toHandle()));
            ProcessTreeTermination.destroyIfNotHost(child.toHandle(), true);
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "a non-host handle must be destroyable");
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void terminatesTheWholeTreeAndReportsItSettled(@TempDir Path tmp) throws Exception {
        Process root = treeFixture(tmp);
        List<ProcessHandle> descendants = root.descendants().toList();

        boolean settled = ProcessTreeTermination.terminateTree(root, GRACEFUL, FORCED);

        assertTrue(settled, "termination must report the tree settled");
        assertFalse(root.isAlive(), "root survived");
        for (ProcessHandle handle : descendants) {
            assertFalse(handle.isAlive(), "descendant " + handle.pid() + " survived");
        }
        assertTrue(ProcessHandle.current().isAlive(), "the host JVM must never be collateral damage");
    }

    @Test
    void terminatingOneJobLeavesAnIndependentJobRunning(@TempDir Path tmp) throws Exception {
        Process jobA = treeFixture(tmp.resolve("a"));
        Process jobB = treeFixture(tmp.resolve("b"));
        try {
            List<ProcessHandle> bHandles = jobB.descendants().toList();

            ProcessTreeTermination.terminateTree(jobA, GRACEFUL, FORCED);

            assertFalse(jobA.isAlive(), "job A must be terminated");
            assertTrue(jobB.isAlive(), "terminating job A must not touch job B");
            for (ProcessHandle handle : bHandles) {
                assertTrue(handle.isAlive(), "job B descendant " + handle.pid() + " was killed with job A");
            }
        } finally {
            ProcessTreeTermination.terminateTree(jobB, GRACEFUL, FORCED);
        }
    }

    @Test
    void terminationIsBoundedForAProcessThatIgnoresGracefulRequests(@TempDir Path tmp) throws Exception {
        Process stubborn = sleeper();
        try {
            long startedAt = System.nanoTime();
            ProcessTreeTermination.terminateTree(stubborn, GRACEFUL, FORCED);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertFalse(stubborn.isAlive(), "a stubborn process must still be forced down");
            assertTrue(elapsedMillis < 30_000L, "termination must stay bounded, took " + elapsedMillis + "ms");
        } finally {
            stubborn.destroyForcibly();
        }
    }

    @Test
    void terminatingAnAlreadyDeadProcessIsHarmless() throws Exception {
        Process child = sleeper();
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));

        assertTrue(ProcessTreeTermination.terminateTree(child, GRACEFUL, FORCED));
        assertTrue(ProcessHandle.current().isAlive());
    }

    // ---------------------------------------------------------------- fixtures

    private static Process sleeper() throws IOException {
        return isWindows()
                ? new ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL").start()
                : new ProcessBuilder("sh", "-c", "sleep 60").start();
    }

    /** Root process that spawns a child which in turn spawns a grandchild, then waits. */
    private static Process treeFixture(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path marker = directory.resolve("ready.txt");
        Process root;
        if (isWindows()) {
            Path script = directory.resolve("tree.cmd");
            Files.writeString(script, """
                    @echo off
                    start /b cmd /c "ping -n 60 127.0.0.1 > NUL"
                    echo ready> "%~dp0ready.txt"
                    ping -n 60 127.0.0.1 > NUL
                    """);
            root = new ProcessBuilder("cmd.exe", "/c", script.toString()).start();
        } else {
            Path script = directory.resolve("tree.sh");
            Files.writeString(script, """
                    #!/bin/sh
                    sh -c 'sleep 60' &
                    printf ready > "$(dirname "$0")/ready.txt"
                    sleep 60
                    """);
            makeExecutable(script);
            root = new ProcessBuilder(script.toString()).start();
        }
        awaitFile(marker, root);
        return root;
    }

    private static void makeExecutable(Path script) throws IOException {
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException windowsFilesystem) {
            // POSIX permissions do not apply here.
        }
    }

    /**
     * Waits for the fixture to publish its marker and spawn a descendant. The pause between probes
     * uses {@link Process#waitFor(long, TimeUnit)} rather than a bare sleep: it is a real bounded
     * wait on the process being observed, and it returns immediately if the fixture dies early.
     */
    private static void awaitFile(Path marker, Process root) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker) && !root.descendants().toList().isEmpty()) return;
            if (root.waitFor(50L, TimeUnit.MILLISECONDS)) break; // the fixture exited without arming
        }
        root.destroyForcibly();
        fail("process tree fixture did not start within the timeout");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
