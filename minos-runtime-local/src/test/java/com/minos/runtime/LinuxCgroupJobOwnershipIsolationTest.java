package com.minos.runtime;

import com.minos.runtime.CgroupJobOwnership.Mark;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two MINOS instances sharing one delegated cgroup v2 root: the sweep of the second instance must
 * never kill the jobs of the first, while a job whose owner died is reclaimed.
 *
 * <p>Requires a delegated cgroup v2 root (see {@link LinuxCgroupJob#ROOT_ENVIRONMENT_VARIABLE});
 * skipped everywhere else, including Windows and Linux hosts without delegation.</p>
 */
class LinuxCgroupJobOwnershipIsolationTest {

    private static final Path SHELL = Path.of("/bin/sh");

    @Test
    void aSecondMinosInstanceDoesNotKillTheJobsOfTheFirst() throws Exception {
        Path root = requireDelegatedRoot();
        // The first instance is simulated by a foreign token stamped with a live PID: our own.
        Mark firstInstance = Mark.of(ProcessHandle.current(), "0f1e2d3c");
        Path directory = root.resolve(firstInstance.markedName("minos-isolation-" + UUID.randomUUID()));
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            Process sleeper = start(job, "sleep", "600");
            try {
                awaitMembership(job);

                LinuxCgroupJob.reclaimStaleJobs(root);

                assertTrue(sleeper.isAlive(), "the sweep of another MINOS instance must not kill a live job");
                assertTrue(Files.isDirectory(directory), "the cgroup of a live MINOS instance must remain");
                assertTrue(job.aliveProcesses() > 0L, "the job must still hold its process");
            } finally {
                sleeper.destroyForcibly();
                sleeper.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            job.close();
        }
    }

    @Test
    void aJobWhoseOwningMinosProcessDiedIsReclaimed() throws Exception {
        Path root = requireDelegatedRoot();
        Mark deadOwner = deadOwnerMark();
        Path directory = root.resolve(deadOwner.markedName("minos-orphan-" + UUID.randomUUID()));
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        Process sleeper = start(job, "sleep", "600");
        try {
            awaitMembership(job);

            LinuxCgroupJob.reclaimStaleJobs(root);

            assertTrue(sleeper.waitFor(10, TimeUnit.SECONDS), "an orphaned job must be killed by the sweep");
            assertFalse(Files.exists(directory), "an orphaned cgroup must be reclaimed");
        } finally {
            sleeper.destroyForcibly();
            if (Files.exists(directory)) job.close();
        }
    }

    @Test
    void anUnmarkedLegacyCgroupWithLiveProcessesIsLeftIntact() throws Exception {
        Path root = requireDelegatedRoot();
        Path directory = root.resolve("minos-legacy-" + UUID.randomUUID());
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            Process sleeper = start(job, "sleep", "600");
            try {
                awaitMembership(job);

                LinuxCgroupJob.reclaimStaleJobs(root);

                assertTrue(sleeper.isAlive(), "an unmarked cgroup with processes has an unknown owner: never killed");
                assertTrue(Files.isDirectory(directory));
            } finally {
                sleeper.destroyForcibly();
                sleeper.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            job.close();
        }
    }

    private static Path requireDelegatedRoot() {
        Optional<Path> root = LinuxCgroupJob.delegatedRoot();
        assumeTrue(root.isPresent(),
                "a delegated cgroup v2 root is required; set " + LinuxCgroupJob.ROOT_ENVIRONMENT_VARIABLE);
        return root.orElseThrow();
    }

    private static Mark deadOwnerMark() throws Exception {
        Process shortLived = new ProcessBuilder("/bin/true").redirectErrorStream(true).start();
        long start = shortLived.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        assertTrue(shortLived.waitFor(10, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(shortLived.pid()).isPresent() && System.nanoTime() < deadline) Thread.sleep(20L);
        return new Mark(shortLived.pid(), start, "0dead0aa");
    }

    private static Process start(LinuxCgroupJob job, String... command) throws Exception {
        List<String> wrapped = job.enterThenExec(SHELL, List.of(command));
        return new ProcessBuilder(wrapped).redirectErrorStream(true).start();
    }

    private static void awaitMembership(LinuxCgroupJob job) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (job.aliveProcesses() == 0L && System.nanoTime() < deadline) Thread.sleep(20L);
        assertEquals(1L, job.aliveProcesses(), "the sleeper must have joined the cgroup: "
                + Files.readString(job.directory().resolve(LinuxCgroupJob.PROCS_FILE), StandardCharsets.UTF_8));
    }
}
