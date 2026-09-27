package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
