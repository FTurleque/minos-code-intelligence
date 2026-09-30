package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Mark;
import com.minos.runtime.local.LinuxCgroupJob.SweepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.minos.runtime.local.CgroupSweepFixtures.KERNEL_LIKE;
import static com.minos.runtime.local.CgroupSweepFixtures.OTHER_PID_NAMESPACE;
import static com.minos.runtime.local.CgroupSweepFixtures.OWNER;
import static com.minos.runtime.local.CgroupSweepFixtures.SELF;
import static com.minos.runtime.local.CgroupSweepFixtures.cgroup;
import static com.minos.runtime.local.CgroupSweepFixtures.context;
import static com.minos.runtime.local.CgroupSweepFixtures.contextWithPlainRemoval;
import static com.minos.runtime.local.CgroupSweepFixtures.killSwitch;
import static com.minos.runtime.local.CgroupSweepFixtures.nobodyIsAlive;
import static com.minos.runtime.local.CgroupSweepFixtures.onlyAlive;
import static com.minos.runtime.local.CgroupSweepFixtures.unreadable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the stale sweep does to a root, on a temporary directory standing for the delegated cgroup root
 * and with the process table, the removal and the bounds injected: a cgroup is killed only when its
 * owner is proven dead, removed only when it is empty, and every cgroup left in place is counted and
 * named as a residue.
 */
class LinuxCgroupStaleRecoveryTest {

    @Test
    void aRemovalThatFailsLeavesAResidueNotAReclaim(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-empty-with-residue"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);
        Files.writeString(stale.resolve("unexpected-residue"), "x", StandardCharsets.UTF_8);

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(
                root, contextWithPlainRemoval(nobodyIsAlive())), "a removal failure never disables containment");

        assertTrue(Files.exists(stale), "the cgroup is still there");
        assertEquals(List.of(), sweep.reclaimed(), "what is still there was not reclaimed");
        assertEquals(List.of("minos-empty-with-residue"), sweep.leftIntact());
        assertTrue(sweep.residues().getFirst().reason().contains("could not remove"), sweep.toString());
    }

    @Test
    void anEmptyStaleCgroupIsReclaimedOnceItIsRemoved(@TempDir Path root) throws Exception {
        Path stale = cgroup(root, "minos-empty", "");

        LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive()));

        assertEquals(List.of("minos-empty"), sweep.reclaimed());
        assertEquals(List.of(), sweep.residues());
        assertFalse(Files.exists(stale));
    }

    @Test
    void anEmptyUnmarkedCgroupIsRemovedWithoutEverWritingTheKillSwitch(@TempDir Path root) throws Exception {
        cgroup(root, "minos-legacy-empty", "");
        List<String> killSwitchAtRemoval = new ArrayList<>();
        SweepContext spying = new SweepContext(SELF, path -> nobodyIsAlive(), path -> {
            killSwitchAtRemoval.add(killSwitch(path));
            KERNEL_LIKE.remove(path);
        }, 4_096L, 2, 0L);

        LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root, spying);

        assertEquals(List.of("minos-legacy-empty"), sweep.reclaimed());
        assertEquals(List.of("0"), killSwitchAtRemoval, "an empty cgroup is removed, never killed");
    }

    @Test
    void orphanedLiveCgroupWithoutKernelKillControlFailsClosed(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve(OWNER.markedName("minos-live")));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertTrue(failure.getMessage().contains("cgroup.kill"));
        assertTrue(Files.exists(stale), "unverified live containment residue must remain visible");
    }

    @Test
    void theEmptyJobOfADeadOwnerIsRemoved(@TempDir Path root) throws Exception {
        Path stale = cgroup(root, OWNER.markedName("minos-orphan"), "");

        LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive()));

        assertEquals(List.of(stale.getFileName().toString()), sweep.reclaimed());
        assertFalse(Files.exists(stale));
    }

    @Test
    void aCgroupOwnedByAnotherLiveMinosProcessIsLeftIntact(@TempDir Path root) throws Exception {
        Path live = cgroup(root, OWNER.markedName("minos-provider-other"), "424242\n");

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(
                root, context(onlyAlive(OWNER.pid(), OWNER.start()))),
                "a job owned by another live MINOS process must not be killed by the sweep");

        assertTrue(Files.exists(live), "the cgroup of a live MINOS instance must remain untouched");
        assertEquals("424242\n", Files.readString(live.resolve(LinuxCgroupJob.PROCS_FILE), StandardCharsets.UTF_8));
        assertEquals("0", killSwitch(live), "no kill was requested");
        assertEquals(List.of(String.valueOf(live.getFileName())), sweep.leftIntact());
    }

    /**
     * R2: a wall-clock step (NTP) shifts the start instant two JVMs derive for the SAME live process.
     * The marks below are the ones a live MINOS instance wrote before such a step, and the sweep must not
     * mistake that shift for a PID reuse and kill the live instance's job.
     */
    @Test
    void aWallClockStepNeverMakesALiveOwnerLookReused(@TempDir Path root) throws Exception {
        long start = 1_700_000_100_000L;
        List<String> names = List.of(
                "minos-provider-stepped-ahead.own-" + OWNER.pid() + "-" + (start + 60_000L) + "-0f1e2d3c",
                "minos-provider-stepped-back.own-" + OWNER.pid() + "-" + (start - 60_000L) + "-0f1e2d3c");
        for (String name : names) cgroup(root, name, "424242\n");

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(
                root, context(onlyAlive(OWNER.pid(), OWNER.start()))),
                "a start-instant shift of a live owner must never lead the sweep to kill its job");

        for (String name : names) {
            assertTrue(Files.exists(root.resolve(name)), name);
            assertEquals("0", killSwitch(root.resolve(name)), name);
        }
        assertEquals(List.of(), sweep.reclaimed());
        assertEquals(names.stream().sorted().toList(), sweep.leftIntact().stream().sorted().toList());
    }

    @Test
    void aCgroupOwnedByThisJvmIsLeftIntact(@TempDir Path root) throws Exception {
        Path own = cgroup(root, SELF.markedName("minos-provider-own"), "424242\n");

        assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertTrue(Files.exists(own), "our own job must never be reclaimed by our own sweep");
        assertEquals("0", killSwitch(own));
    }

    @Test
    void anUnmarkedCgroupWithLiveProcessesIsLeftIntact(@TempDir Path root) throws Exception {
        Path legacy = cgroup(root, "minos-legacy-live", "424242\n");

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())),
                "an unmarked cgroup with processes has an unknown owner and must not be killed");

        assertTrue(Files.exists(legacy));
        assertEquals("0", killSwitch(legacy));
        assertEquals(List.of("minos-legacy-live"), sweep.leftIntact(), "the unexplained residue must be reported");
        assertEquals(List.of(), sweep.reclaimed());
    }

    @Test
    void unreadableStaleMembershipFailsClosed(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-unknown"));
        Files.createDirectory(stale.resolve(LinuxCgroupJob.PROCS_FILE));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertTrue(failure.getMessage().contains("unable to read cgroup membership"));
        assertTrue(Files.exists(stale), "unknown membership must not be reported as reclaimed");
    }

    @Test
    void nonMinosDirectoriesAreNeverTouched(@TempDir Path root) throws Exception {
        Path foreign = cgroup(root, "foreign-job", "424242\n");

        LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive()));

        assertTrue(Files.exists(foreign));
        assertEquals("0", killSwitch(foreign));
    }

    /**
     * The default conclusion: when the process table cannot say whether the owner is alive, the job is
     * left alone and named as a residue, however dead the owner looks.
     */
    @Test
    void anOwnerTheProcessTableCannotVerifyKeepsItsJobAndItsProcesses(@TempDir Path root) throws Exception {
        Path job = cgroup(root, OWNER.markedName("minos-provider-unverified"), "424242\n");

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(
                root, context(unreadable("the process table cannot be read"))));

        assertTrue(Files.exists(job));
        assertEquals("0", killSwitch(job), "no kill was requested");
        assertEquals(List.of(), sweep.reclaimed());
        assertTrue(sweep.residues().getFirst().reason().contains("the process table cannot be read"), sweep.toString());
    }

    @Test
    void aJobOfAnotherPidNamespaceIsNeverKilled(@TempDir Path root) throws Exception {
        Mark foreign = OWNER.withNamespaces(OTHER_PID_NAMESPACE);
        Path job = cgroup(root, foreign.markedName("minos-provider-foreign"), "424242\n");

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertTrue(Files.exists(job));
        assertEquals("0", killSwitch(job), "its pid looks absent here only because it is numbered elsewhere");
        assertEquals(List.of(), sweep.reclaimed());
    }

    /**
     * Members of another PID namespace show up as PID 0 in {@code cgroup.procs}; they cannot be named, so
     * the membership is unreadable and the sweep fails closed before it ever writes the kill switch.
     */
    @Test
    void membersOfAnotherPidNamespaceAreNeverKilled(@TempDir Path root) throws Exception {
        Path job = cgroup(root, OWNER.markedName("minos-provider-invisible"), "0\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertTrue(failure.getMessage().contains("unable to read cgroup membership"), failure.getMessage());
        assertEquals("0", killSwitch(job), "no kill was requested");
    }

    @Test
    void aDeadOwnersProcessesInANestedCgroupAreKilled(@TempDir Path root) throws Exception {
        Path job = cgroup(root, OWNER.markedName("minos-provider-nested"), "");
        cgroup(job, "inner", "424242\n");

        // The fake cannot make the process die: the kill is attempted, then verified, and the verification fails.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertEquals("1", killSwitch(job), "the owner is dead: the kernel kill reaches the nested cgroup");
        assertTrue(failure.getMessage().contains("still contains 1 process(es)"), failure.getMessage());
    }

    @Test
    void anUnmarkedCgroupWithProcessesInANestedCgroupIsLeftIntact(@TempDir Path root) throws Exception {
        Path job = cgroup(root, "minos-legacy-nested", "");
        cgroup(job, "inner", "424242\n");

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(
                () -> LinuxCgroupJob.reclaimStaleJobs(root, context(nobodyIsAlive())));

        assertTrue(Files.exists(job));
        assertEquals("0", killSwitch(job));
        assertEquals(List.of("minos-legacy-nested"), sweep.leftIntact());
        assertEquals(List.of(), sweep.reclaimed());
    }

    @Test
    void aSweepBoundedBelowTheEntryCountSaysWhatItDidNotExamine(@TempDir Path root) throws Exception {
        for (int index = 0; index < 5; index++) cgroup(root, "minos-empty-" + index, "");
        SweepContext bounded = new SweepContext(SELF, path -> nobodyIsAlive(), KERNEL_LIKE, 3L, 2, 0L);

        LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root, bounded);

        assertEquals(3, sweep.reclaimed().size(), sweep.toString());
        assertEquals(2, sweep.notExamined(), "the entries past the bound are counted, not silently ignored");
    }

    @Test
    void aSweepWithinItsBoundExaminesEverything(@TempDir Path root) throws Exception {
        for (int index = 0; index < 3; index++) cgroup(root, "minos-empty-" + index, "");
        SweepContext exact = new SweepContext(SELF, path -> nobodyIsAlive(), KERNEL_LIKE, 3L, 2, 0L);

        LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root, exact);

        assertEquals(3, sweep.reclaimed().size(), sweep.toString());
        assertEquals(0, sweep.notExamined());
    }
}
