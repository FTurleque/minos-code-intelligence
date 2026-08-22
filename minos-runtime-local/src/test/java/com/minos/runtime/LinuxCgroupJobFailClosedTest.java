package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
