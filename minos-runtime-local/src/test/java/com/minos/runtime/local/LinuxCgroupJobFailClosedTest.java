package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxCgroupJobFailClosedTest {

    @Test
    void missingKernelKillControlFailsClosedWithoutPidFallback(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("job"));
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(1, 0L));

        assertTrue(failure.getMessage().contains("cgroup.kill"));
        assertTrue(Files.exists(directory), "failed containment must not be reported as reclaimed");
    }

    @Test
    void kernelKillWriteFailureIsNotSilentlyDowngraded(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("job"));
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);
        Files.createDirectory(directory.resolve("cgroup.kill"));
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(1, 0L));

        assertTrue(failure.getMessage().contains("could not be triggered"));
    }

    @Test
    void survivingMembershipAfterKernelKillIsAContainmentFailure(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("job"));
        Files.writeString(directory.resolve("cgroup.kill"), "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(2, 0L));

        assertTrue(failure.getMessage().contains("still contains 1 process"));
    }

    @Test
    void membershipReadFailureAfterKernelKillFailsClosed(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("job"));
        Files.writeString(directory.resolve("cgroup.kill"), "", StandardCharsets.UTF_8);
        Files.createDirectory(directory.resolve(LinuxCgroupJob.PROCS_FILE));
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(1, 0L));

        assertTrue(failure.getMessage().contains("unable to verify cgroup membership"));
    }

    @Test
    void interruptedTerminationVerificationPreservesInterruptAndFailsClosed(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("job"));
        Files.writeString(directory.resolve("cgroup.kill"), "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        Thread.currentThread().interrupt();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(2, 1L));
            assertTrue(failure.getMessage().contains("interrupted while verifying cgroup termination"));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be restored");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * R3: a containment failure reaches the provider execution report and the journal; it names the
     * cgroup relative to the delegated root, never by absolute path, including in its causes.
     */
    @Test
    void containmentFailuresNeverCarryAnAbsolutePath(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("minos-job"));
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
        LinuxCgroupJob withoutKillSwitch = new LinuxCgroupJob(directory);
        Path unreadable = Files.createDirectory(temp.resolve("minos-unreadable"));
        Files.createDirectory(unreadable.resolve(LinuxCgroupJob.PROCS_FILE));
        LinuxCgroupJob withUnreadableMembership = new LinuxCgroupJob(unreadable);

        IllegalStateException kill = assertThrows(IllegalStateException.class, () -> withoutKillSwitch.kill(1, 0L));
        IllegalStateException close = assertThrows(IllegalStateException.class, withoutKillSwitch::close);
        IllegalStateException membership = assertThrows(
                IllegalStateException.class, withUnreadableMembership::aliveProcesses);
        IOException escape = assertThrows(
                IOException.class, () -> LinuxCgroupJob.create(temp, "minos-x", LinuxCgroupJob.Limits.DEFAULT));
        IOException ownershipEscape = assertThrows(
                IOException.class, () -> LinuxCgroupJob.createOwnershipOnly(temp, "minos-x"));

        for (Throwable failure : List.of(kill, close, membership, escape, ownershipEscape)) {
            LinuxCgroupJobDiagnosticsTest.assertPathFree(failure, temp);
        }
        assertTrue(kill.getMessage().contains("minos-job"), kill.getMessage());
        assertTrue(kill.getMessage().contains("cgroup.kill"), kill.getMessage());
        assertTrue(membership.getMessage().contains("minos-unreadable"), membership.getMessage());
        assertTrue(escape.getMessage().contains("minos-x"), escape.getMessage());
    }

    /**
     * W3: the orchestration persists an interrupted, resumable run when it finds an
     * {@link InterruptedException} in the cause chain (R1). Path redaction must never hide it.
     */
    @Test
    void anInterruptedTerminationVerificationKeepsItsInterruptedExceptionCause(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("minos-job"));
        Files.writeString(directory.resolve("cgroup.kill"), "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        Thread.currentThread().interrupt();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(2, 1L));

            assertTrue(failure.getCause() instanceof InterruptedException, String.valueOf(failure.getCause()));
            assertTrue(isInterruption(failure), "the run must be persisted as interrupted, not failed");
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be restored");
            LinuxCgroupJobDiagnosticsTest.assertPathFree(failure, temp);
        } finally {
            Thread.interrupted();
        }
    }

    /** W3: a cause that carries no path is attached as it is, with its own class. */
    @Test
    void aCauseWithoutPathIsAttachedUnchanged(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("minos-job"));
        Files.writeString(directory.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> job.kill(1, 0L));

        assertEquals(IOException.class, failure.getCause().getClass(), String.valueOf(failure.getCause()));
        assertTrue(failure.getCause().getMessage().contains("minos-job"), failure.getCause().getMessage());
        LinuxCgroupJobDiagnosticsTest.assertPathFree(failure, temp);
    }

    /**
     * W3: a cause that carries a path is rewritten, but keeps the fully qualified name of its original
     * class, its stack trace, its deeper causes and its suppressed failures (each rewritten only when it
     * carries a path itself); an interruption and a path-free failure pass through as they are.
     */
    @Test
    void aRedactedCauseKeepsItsOriginalClassStackCausesAndSuppressedFailures(@TempDir Path root) {
        String file = root.resolve("minos-x").resolve(LinuxCgroupJob.PROCS_FILE).toString();
        String relative = Path.of("minos-x", LinuxCgroupJob.PROCS_FILE).toString();
        InterruptedException interruption = new InterruptedException("stop requested");
        NoSuchFileException deep = new NoSuchFileException(file);
        deep.initCause(interruption);
        IOException top = new IOException("read failed for " + file, deep);
        FileSystemException busy = new FileSystemException(file, null, "Device or resource busy");
        IllegalStateException pathFree = new IllegalStateException("no path here");
        top.addSuppressed(busy);
        top.addSuppressed(pathFree);

        Throwable redacted = LinuxCgroupJob.redactCause(top, root);

        LinuxCgroupJobDiagnosticsTest.assertPathFree(redacted, root);
        assertTrue(redacted.toString().contains("java.io.IOException: read failed for " + relative), redacted.toString());
        assertArrayEquals(top.getStackTrace(), redacted.getStackTrace());
        Throwable redactedDeep = redacted.getCause();
        assertTrue(redactedDeep.toString().contains("java.nio.file.NoSuchFileException: " + relative),
                String.valueOf(redactedDeep));
        assertArrayEquals(deep.getStackTrace(), redactedDeep.getStackTrace());
        assertSame(interruption, redactedDeep.getCause(), "an interruption is never rewritten");
        assertTrue(isInterruption(redacted));
        assertEquals(2, redacted.getSuppressed().length, "no suppressed failure is lost");
        assertTrue(redacted.getSuppressed()[0].toString().contains("java.nio.file.FileSystemException"),
                redacted.getSuppressed()[0].toString());
        assertTrue(redacted.getSuppressed()[0].toString().contains("Device or resource busy"));
        assertSame(pathFree, redacted.getSuppressed()[1], "a path-free failure is kept as it is");

        assertSame(interruption, LinuxCgroupJob.redactCause(interruption, root));
        assertSame(pathFree, LinuxCgroupJob.redactCause(pathFree, root));
    }

    /** Same walk as {@code IndexingRunExecutor.isInterruption} (minos-application), which decides R1. */
    private static boolean isInterruption(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
}
