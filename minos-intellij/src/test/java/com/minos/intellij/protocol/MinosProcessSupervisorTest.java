package com.minos.intellij.protocol;

import com.intellij.openapi.progress.ProcessCanceledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MinosProcessSupervisorTest {

    @TempDir
    Path tmp;

    // 1 — Normal completion: stdout captured, exit 0
    @Test
    void normalCompletion() throws Exception {
        List<String> cmd = isWindows()
                ? List.of("cmd", "/d", "/c", "echo hello")
                : List.of("sh", "-c", "printf hello");
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(new ProcessBuilder(cmd).start())) {
            boolean completed = supervisor.waitFor(10_000);
            assertTrue(completed, "process should complete");
            supervisor.drainOutput();
            assertEquals(0, supervisor.exitValue());
            assertTrue(supervisor.stdout().contains("hello"), "stdout must contain 'hello'");
        }
    }

    // 2 — Non-zero exit: code propagated correctly
    @Test
    void nonZeroExit() throws Exception {
        List<String> cmd = isWindows()
                ? List.of("cmd", "/d", "/c", "exit 42")
                : List.of("sh", "-c", "exit 42");
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(new ProcessBuilder(cmd).start())) {
            boolean completed = supervisor.waitFor(10_000);
            assertTrue(completed, "process should complete");
            supervisor.drainOutput();
            assertEquals(42, supervisor.exitValue());
        }
    }

    // 3 — Timeout kills entire tree: all PIDs dead after stop(null)
    @Test
    void timeoutKillsEntireTree() throws Exception {
        Path marker = tmp.resolve("ready.txt");
        Process root = startTreeFixture(marker);
        awaitFile(marker, 10);
        List<ProcessHandle> handles = collectHandles(root);

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            supervisor.stop(null);
        }

        assertAllDead(handles);
    }

    // 4 — Cancel kills entire tree: ProcessCanceledException rethrown, all PIDs dead
    @Test
    void cancelKillsEntireTree() throws Exception {
        Path marker = tmp.resolve("ready.txt");
        Process root = startTreeFixture(marker);
        awaitFile(marker, 10);
        List<ProcessHandle> handles = collectHandles(root);

        ProcessCanceledException pce = new ProcessCanceledException();
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            ProcessCanceledException thrown = assertThrows(ProcessCanceledException.class,
                    () -> supervisor.stop(pce));
            assertSame(pce, thrown, "original PCE instance must be rethrown");
        }

        assertAllDead(handles);
    }

    // 5 — Recalcitrant process ignoring SIGTERM must be force-killed
    @Test
    @EnabledOnOs(OS.LINUX)
    void recalcitrantProcessForcedKill() throws Exception {
        Path script = tmp.resolve("stubborn.sh");
        Files.writeString(script, "#!/bin/sh\ntrap '' TERM\nsleep 300\n");
        makeExecutable(script);

        Process root = new ProcessBuilder(script.toString()).start();
        List<ProcessHandle> handles = collectHandles(root);
        assertTrue(root.isAlive(), "stubborn process must be running");

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            supervisor.stop(null);
        }

        assertAllDead(handles);
    }

    // 6 — .cmd wrapper on Windows: descendants killed through cmd.exe wrapping layer
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cmdWrapperWindowsAllDescendantsKilled() throws Exception {
        Path marker = tmp.resolve("ready.txt");
        // Script has the marker path embedded — MinosCommandLine needs no extra arguments here
        Path cmdScript = writeCmdTreeFixture(marker);

        // Route through cmd.exe via MinosCommandLine — mirrors real plugin invocation
        List<String> cmd = MinosCommandLine.build(cmdScript.toString(), List.of(), "Windows 10");
        Process root = new ProcessBuilder(cmd).start();
        awaitFile(marker, 15);
        List<ProcessHandle> handles = collectHandles(root);

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            supervisor.stop(null);
        }

        assertAllDead(handles);
    }

    // 7 — Direct executable on Unix: grandchild killed
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void directExecutableUnixAllDescendantsKilled() throws Exception {
        Path marker = tmp.resolve("ready.txt");
        Path script = writeUnixTreeFixture(marker);
        Process root = new ProcessBuilder(script.toString()).start();
        awaitFile(marker, 10);
        List<ProcessHandle> handles = collectHandles(root);

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            supervisor.stop(null);
        }

        assertAllDead(handles);
    }

    // 8 — Explicit PID-level check: every PID in the tree individually confirmed dead
    @Test
    void parentChildGrandchildAllDead() throws Exception {
        Path marker = tmp.resolve("ready.txt");
        Process root = startTreeFixture(marker);
        awaitFile(marker, 10);

        ProcessHandle rootHandle = root.toHandle();
        List<ProcessHandle> descendantHandles = root.descendants().toList();
        assertFalse(descendantHandles.isEmpty(), "fixture must have at least one descendant");

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            supervisor.stop(null);
        }

        assertFalse(rootHandle.isAlive(), "root pid=" + rootHandle.pid() + " must be dead");
        for (ProcessHandle handle : descendantHandles) {
            assertFalse(handle.isAlive(), "descendant pid=" + handle.pid() + " must be dead");
        }
    }

    // 9 — Descendant holding stdout open must not block drain indefinitely
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void descendantHoldingStdoutOpenDoesNotBlockDrain() throws Exception {
        Path marker = tmp.resolve("ready.txt");
        Path script = tmp.resolve("pipe-holder.sh");
        Files.writeString(script, """
                #!/bin/sh
                sh -c 'while true; do sleep 1; done' &
                printf ready > '%s'
                sleep 300
                """.formatted(marker.toString()));
        makeExecutable(script);

        Process root = new ProcessBuilder(script.toString()).start();
        awaitFile(marker, 10);

        long startNs = System.nanoTime();
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root)) {
            supervisor.stop(null);
        }
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNs);

        assertFalse(root.isAlive(), "root must be dead after stop");
        assertTrue(elapsedSeconds < 15,
                "stop() must complete within 15 s even with a pipe-holding descendant, took "
                        + elapsedSeconds + " s");
    }

    // 10 — Cancelling A does not kill B
    @Test
    void twoSimultaneousCommandsCancelADoesNotKillB() throws Exception {
        List<String> longRunning = longRunningCommand();

        Process pA = new ProcessBuilder(longRunning).start();
        Process pB = new ProcessBuilder(longRunning).start();
        assertTrue(pB.isAlive(), "B must be running before the test");

        // Nested try-with-resources: supervisorB closes B after we verify A's cancel didn't
        // affect it; supervisorA is closed (no-op — stop already ran) after the assertThrows.
        try (MinosProcessSupervisor supervisorB = new MinosProcessSupervisor(pB)) {
            try (MinosProcessSupervisor supervisorA = new MinosProcessSupervisor(pA)) {
                ProcessCanceledException pce = new ProcessCanceledException();
                assertThrows(ProcessCanceledException.class, () -> supervisorA.stop(pce));
            }
            assertTrue(pB.isAlive(), "process B must still be alive after cancelling A");
        }
    }

    // -----------------------------------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------------------------------

    private Process startTreeFixture(Path marker) throws IOException {
        if (isWindows()) {
            Path script = writeCmdTreeFixture(marker);
            // Direct cmd.exe invocation (not via MinosCommandLine) — for the generic tree tests
            return new ProcessBuilder("cmd", "/d", "/c", script.toString()).start();
        } else {
            return new ProcessBuilder(writeUnixTreeFixture(marker).toString()).start();
        }
    }

    /** Unix: shell → sleep grandchild; writes marker when both are running. */
    private Path writeUnixTreeFixture(Path marker) throws IOException {
        Path script = tmp.resolve("tree-fixture.sh");
        Files.writeString(script, """
                #!/bin/sh
                sh -c 'sleep 300' &
                printf ready > '%s'
                sleep 300
                """.formatted(marker.toString()));
        makeExecutable(script);
        return script;
    }

    /**
     * Windows .cmd fixture: creates a multi-level tree using cmd.exe and ping.exe (both available
     * without PATH manipulation; ping works without an interactive console unlike timeout.exe).
     *
     * <p>Tree when run as {@code cmd /d /c tree-fixture.cmd}:
     * <pre>
     * cmd.exe /d /c tree-fixture.cmd        (Level 1 — the Process object)
     *   ├─ cmd.exe /d /c "ping -n 3600 …"  (Level 2 — started by start /B)
     *   │    └─ ping.exe                   (Level 3a — child of Level 2 cmd.exe)
     *   └─ ping.exe                        (Level 2b — batch file's own ping call)
     * </pre>
     *
     * <p>The marker path is embedded directly so no argument passing through MinosCommandLine
     * quoting is needed — keeps test 6 independent of MinosCommandLine's quote rules.
     */
    private Path writeCmdTreeFixture(Path marker) throws IOException {
        Path script = tmp.resolve("tree-fixture.cmd");
        // Marker path embedded as a literal: backslashes are safe in cmd.exe paths
        Files.writeString(script, """
                @echo off
                start "" /B cmd /d /c "ping -n 3600 127.0.0.1 > nul"
                echo.>"%s"
                ping -n 3600 127.0.0.1 > nul
                """.formatted(marker.toString()));
        return script;
    }

    /**
     * A long-running command that does NOT require an interactive console.
     * <p>On Windows: ping sends ICMP replies and runs comfortably for 3600 seconds regardless of
     * whether stdin is a console — unlike timeout.exe which exits immediately without one.
     */
    private static List<String> longRunningCommand() {
        if (isWindows()) {
            return List.of("cmd", "/d", "/c", "ping -n 3600 127.0.0.1 > nul");
        }
        return List.of("sh", "-c", "sleep 300");
    }

    // -----------------------------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------------------------

    private static void makeExecutable(Path script) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(script, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows FS — POSIX permissions are not applicable
        }
    }

    private static void awaitFile(Path file, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!Files.exists(file)) {
            if (System.nanoTime() >= deadline)
                fail("Timed out waiting for marker file: " + file);
            Thread.sleep(50); // NOSONAR: polling loop in test fixture — no shared state to synchronize
        }
    }

    /**
     * Collects the root {@link ProcessHandle} and all current descendants. Uses handle identity
     * (PID + start time) rather than raw PIDs so that PID reuse on busy CI runners does not
     * produce false-positive liveness results in {@link #assertAllDead}.
     */
    private static List<ProcessHandle> collectHandles(Process root) {
        List<ProcessHandle> handles = new ArrayList<>();
        handles.add(root.toHandle());
        root.descendants().forEach(handles::add);
        return List.copyOf(handles);
    }

    private static void assertAllDead(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            assertFalse(handle.isAlive(), "PID " + handle.pid() + " should be dead after stop");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
