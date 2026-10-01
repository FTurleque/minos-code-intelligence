package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Mark;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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

    /**
     * R2 on a real cgroup: the first instance marked its job before a wall-clock step, so the start
     * instant in its mark is a minute away from the one this JVM computes for the same live PID.
     */
    @Test
    void aWallClockStepDoesNotKillTheJobsOfALiveMinosInstance() throws Exception {
        Path root = requireDelegatedRoot();
        long pid = ProcessHandle.current().pid();
        long stepped = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + 60_000L;
        Path directory = root.resolve("minos-stepped-" + UUID.randomUUID() + ".own-" + pid + "-" + stepped + "-0f1e2d3c");
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            Process sleeper = start(job, "sleep", "600");
            try {
                awaitMembership(job);

                LinuxCgroupJob.reclaimStaleJobs(root);

                assertTrue(sleeper.isAlive(), "a wall-clock step must never make a live owner look reused");
                assertTrue(Files.isDirectory(directory), "the cgroup of a live MINOS instance must remain");
                assertTrue(job.aliveProcesses() > 0L, "the job must still hold its process");
            } finally {
                sleeper.destroyForcibly();
                sleeper.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            if (Files.exists(directory)) job.close();
        }
    }

    /** R2 on a real cgroup: the mark written by this JVM carries the kernel start ticks of its process. */
    @Test
    void theMarkCarriesTheKernelStartTicksAndNamespacesOfItsOwner() {
        Path root = requireDelegatedRoot();
        OptionalLong ticks = CgroupJobOwnership.startTicks(CgroupJobOwnership.PROC, ProcessHandle.current().pid());

        assertTrue(ticks.isPresent(), "/proc/self/stat must expose the start ticks on Linux");
        assertEquals(ticks.getAsLong(), CgroupJobOwnership.CURRENT.start());
        assertEquals(CgroupJobOwnership.StartClock.BOOT_TICKS, CgroupJobOwnership.CURRENT.clock());
        CgroupJobOwnership.OwnerStatus self = CgroupJobOwnership.OwnerLookup.system(root)
                .find(ProcessHandle.current().pid());
        assertEquals(CgroupJobOwnership.OwnerStatus.Presence.PRESENT, self.presence(), self.reason());
        assertEquals(ticks, self.startTicks(), "the sweep reads the same ticks for the same live process");
        assertTrue(CgroupJobOwnership.CURRENT.namespaces().known(),
                "/proc/self/ns/pid must be readable on Linux: the mark is stamped with it");
    }

    /**
     * R2 on a real cgroup: a PID whose kernel start ticks differ from the mark designates another
     * process (PID reuse), so the job is reclaimed and its processes are killed.
     */
    @Test
    void aJobWhoseOwnerPidWasReusedIsReclaimedOnItsKernelStartTicks() throws Exception {
        Path root = requireDelegatedRoot();
        long pid = ProcessHandle.current().pid();
        long ticks = CgroupJobOwnership.startTicks(CgroupJobOwnership.PROC, pid).orElseThrow();
        Mark reused = new Mark(pid, ticks + 1L, "0f1e2d3c").withNamespaces(CgroupJobOwnership.CURRENT.namespaces());
        Path directory = root.resolve(reused.markedName("minos-reused-" + UUID.randomUUID()));
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        Process sleeper = start(job, "sleep", "600");
        try {
            awaitMembership(job);

            LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root);

            assertTrue(sleeper.waitFor(10, TimeUnit.SECONDS), "a job of a reused pid must be killed by the sweep");
            assertFalse(Files.exists(directory), "a job of a reused pid must be reclaimed");
            assertTrue(sweep.reclaimed().contains(String.valueOf(directory.getFileName())));
        } finally {
            sleeper.destroyForcibly();
            if (Files.exists(directory)) job.close();
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

    /**
     * A PID is only meaningful in the PID namespace it was written in: an owner of another namespace looks
     * absent here (or is another process here), which proves nothing about its death. Its job is left alone.
     */
    @Test
    void aJobStampedWithAnotherPidNamespaceIsNeverKilled() throws Exception {
        Path root = requireDelegatedRoot();
        CgroupJobOwnership.Namespaces ours = CgroupJobOwnership.CURRENT.namespaces();
        Mark foreign = deadOwnerMark().withNamespaces(
                new CgroupJobOwnership.Namespaces(ours.pid() + 1L, ours.time()));
        Path directory = root.resolve(foreign.markedName("minos-foreign-pidns-" + UUID.randomUUID()));
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            Process sleeper = start(job, "sleep", "600");
            try {
                awaitMembership(job);

                LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root);

                assertTrue(sleeper.isAlive(), "a job of another PID namespace must never be killed");
                assertTrue(Files.isDirectory(directory));
                assertTrue(sweep.leftIntact().contains(String.valueOf(directory.getFileName())), sweep.toString());
            } finally {
                sleeper.destroyForcibly();
                sleeper.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            if (Files.exists(directory)) job.close();
        }
    }

    /** Start ticks read in another time namespace are shifted: a live owner must not look like a PID reuse. */
    @Test
    void aJobStampedWithAnotherTimeNamespaceIsNeverKilled() throws Exception {
        Path root = requireDelegatedRoot();
        long pid = ProcessHandle.current().pid();
        long ticks = CgroupJobOwnership.startTicks(CgroupJobOwnership.PROC, pid).orElseThrow();
        CgroupJobOwnership.Namespaces ours = CgroupJobOwnership.CURRENT.namespaces();
        Mark shifted = new Mark(pid, ticks + 100_000L, "0f1e2d3c")
                .withNamespaces(new CgroupJobOwnership.Namespaces(ours.pid(), ours.time() + 1L));
        Path directory = root.resolve(shifted.markedName("minos-foreign-timens-" + UUID.randomUUID()));
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            Process sleeper = start(job, "sleep", "600");
            try {
                awaitMembership(job);

                LinuxCgroupJob.reclaimStaleJobs(root);

                assertTrue(sleeper.isAlive(), "ticks of another time namespace are not comparable: never kill");
                assertTrue(Files.isDirectory(directory));
            } finally {
                sleeper.destroyForcibly();
                sleeper.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            if (Files.exists(directory)) job.close();
        }
    }

    /**
     * A dead owner's job whose processes sit in a cgroup below it: the membership of the job itself is
     * empty, but the processes are its. The kernel kill reaches them, and the job is removed with its
     * nested cgroup, instead of being reported reclaimed while everything is still there.
     */
    @Test
    void aDeadOwnersNestedCgroupIsKilledAndRemovedWithIt() throws Exception {
        Path root = requireDelegatedRoot();
        Mark deadOwner = deadOwnerMark();
        Path directory = root.resolve(deadOwner.markedName("minos-nested-" + UUID.randomUUID()));
        Files.createDirectory(directory);
        Path innerDirectory = Files.createDirectory(directory.resolve("inner"));
        LinuxCgroupJob inner = new LinuxCgroupJob(innerDirectory);
        Process sleeper = start(inner, "sleep", "600");
        try {
            awaitMembership(inner);

            LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root);

            assertTrue(sleeper.waitFor(10, TimeUnit.SECONDS), "the nested process of a dead owner must be killed");
            assertFalse(Files.exists(directory), "the job and its nested cgroup must be removed");
            assertTrue(sweep.reclaimed().contains(String.valueOf(directory.getFileName())), sweep.toString());
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor(10, TimeUnit.SECONDS);
            if (Files.exists(innerDirectory)) inner.close();
            Files.deleteIfExists(directory);
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

    /**
     * R3 on a real delegated root: re-running the qualification reports, in one WARNING and without
     * any absolute path, the populated cgroups its sweep leaves intact (a live foreign MINOS job and
     * an unmarked job), and the root stays usable.
     */
    @Test
    void theQualificationReportsTheResiduesItLeavesIntact() throws Exception {
        Path root = requireDelegatedRoot();
        Path marked = root.resolve(Mark.of(ProcessHandle.current(), "0f1e2d3c")
                .markedName("minos-residue-" + UUID.randomUUID()));
        Path unmarked = root.resolve("minos-residue-legacy-" + UUID.randomUUID());
        Files.createDirectory(marked);
        Files.createDirectory(unmarked);
        LinuxCgroupJob markedJob = new LinuxCgroupJob(marked);
        LinuxCgroupJob unmarkedJob = new LinuxCgroupJob(unmarked);
        Process first = start(markedJob, "sleep", "600");
        Process second = start(unmarkedJob, "sleep", "600");
        try {
            awaitMembership(markedJob);
            awaitMembership(unmarkedJob);
            List<LogRecord> records = new ArrayList<>();

            LinuxCgroupJob.resetDelegationForTesting();
            Optional<Path> requalified = LinuxCgroupJobDiagnosticsTest.capture(records, LinuxCgroupJob::delegatedRoot);

            assertEquals(Optional.of(root), requalified, "residues left intact never disqualify the root");
            assertTrue(first.isAlive() && second.isAlive(), "residues are reported, never killed");
            String markedName = String.valueOf(marked.getFileName());
            String unmarkedName = String.valueOf(unmarked.getFileName());
            List<LogRecord> reports = records.stream()
                    .filter(r -> r.getMessage().contains(markedName) || r.getMessage().contains(unmarkedName))
                    .toList();
            assertEquals(1, reports.size(), "one aggregated report per qualification: " + reports.stream()
                    .map(LogRecord::getMessage).toList());
            assertEquals(Level.WARNING, reports.getFirst().getLevel());
            assertTrue(reports.getFirst().getMessage().contains(markedName), reports.getFirst().getMessage());
            assertTrue(reports.getFirst().getMessage().contains(unmarkedName), reports.getFirst().getMessage());
            LinuxCgroupJobDiagnosticsTest.assertNoAbsolutePath(records, root);
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
            first.waitFor(10, TimeUnit.SECONDS);
            second.waitFor(10, TimeUnit.SECONDS);
            markedJob.close();
            unmarkedJob.close();
        }
    }

    private static Path requireDelegatedRoot() {
        Optional<Path> root = LinuxCgroupJob.delegatedRoot();
        assumeTrue(root.isPresent(),
                "a delegated cgroup v2 root is required; set " + LinuxCgroupJob.ROOT_ENVIRONMENT_VARIABLE);
        return root.orElseThrow();
    }

    private static Mark deadOwnerMark() throws Exception {
        Process shortLived = new ProcessBuilder("/bin/sleep", "0.2").redirectErrorStream(true).start();
        long start = CgroupJobOwnership.startTicks(CgroupJobOwnership.PROC, shortLived.pid()).orElse(0L);
        assertTrue(shortLived.waitFor(10, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(shortLived.pid()).isPresent() && System.nanoTime() < deadline) Thread.sleep(20L);
        return new Mark(shortLived.pid(), start, "0dead0aa").withNamespaces(CgroupJobOwnership.CURRENT.namespaces());
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
