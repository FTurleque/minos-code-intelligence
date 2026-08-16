package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin cannot depend on any MINOS artifact, so it carries its own copy of the host-process
 * policy. This pins that copy to the same invariant the runtime enforces: the IDE JVM is never
 * treated as a CLI-owned descendant, however it enters the supervisor's bookkeeping.
 */
class MinosProcessSupervisorHostProtectionTest {

    @Test
    void theIdeJvmIsNeverRememberedAsOwnedNorDestroyed(@TempDir Path tmp) throws Exception {
        long hostPid = ProcessHandle.current().pid();
        Process child = sleeper();

        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(child)) {
            // Inject the host handle the way a tree-enumeration anomaly or PID reuse would.
            supervisor.rememberForTesting(java.util.List.of(ProcessHandle.current()));

            assertFalse(supervisor.ownsPidForTesting(hostPid),
                    "the IDE JVM must never be recorded as a CLI-owned process");
            assertTrue(ProcessHandle.current().isAlive(),
                    "the IDE JVM must still be alive after ownership bookkeeping");
        } catch (MinosProtocolException tolerated) {
            // stop() reports cleanup detail for the fixture; host survival is what this test pins.
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }

        assertTrue(ProcessHandle.current().isAlive(),
                "the IDE JVM must survive supervisor termination");
    }

    @Test
    void aRealChildIsStillTerminated(@TempDir Path tmp) throws Exception {
        Process child = sleeper();
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(child)) {
            supervisor.stop(null);
        } catch (MinosProtocolException tolerated) {
            // Cleanup diagnostics are not the subject here.
        }
        assertTrue(child.waitFor(20, TimeUnit.SECONDS), "the supervised child must be terminated");
        assertFalse(child.isAlive());
        assertTrue(ProcessHandle.current().isAlive());
    }

    private static Process sleeper() throws IOException {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows
                ? new ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > NUL").start()
                : new ProcessBuilder("sh", "-c", "sleep 60").start();
    }
}
