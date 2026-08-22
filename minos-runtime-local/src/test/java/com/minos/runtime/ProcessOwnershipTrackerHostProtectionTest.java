package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessOwnershipTrackerHostProtectionTest {

    @Test
    void hostJvmCanNeverBecomeProviderOwned(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("Sleeper.java");
        Files.writeString(source, """
                public class Sleeper {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(300_000L);
                    }
                }
                """);
        Process child = new ProcessBuilder(javaExecutable(), source.toString()).start();
        long hostPid = ProcessHandle.current().pid();

        try (ProcessOwnershipTracker tracker = new ProcessOwnershipTracker(child)) {
            tracker.remember(List.of(ProcessHandle.current()));

            assertFalse(tracker.ownsPid(hostPid),
                    "the MINOS host JVM must never enter provider ownership bookkeeping");
            tracker.terminate();
            assertTrue(ProcessHandle.current().isAlive(),
                    "provider cleanup must never terminate the MINOS host JVM");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }
}
