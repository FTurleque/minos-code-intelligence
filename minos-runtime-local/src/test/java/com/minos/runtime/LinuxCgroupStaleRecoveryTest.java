package com.minos.runtime;

import com.minos.runtime.CgroupJobOwnership.Mark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxCgroupStaleRecoveryTest {

    @Test
    void emptyStaleCgroupDeletionFailureDoesNotDisableContainment(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-empty-with-residue"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);
        Files.writeString(stale.resolve("unexpected-residue"), "x", StandardCharsets.UTF_8);

        LinuxCgroupJob.StaleSweep sweep = LinuxCgroupJob.reclaimStaleJobs(root);

        assertTrue(Files.exists(stale), "empty-directory cleanup remains best effort");
        assertEquals(List.of("minos-empty-with-residue"), sweep.reclaimed(), "an unmarked empty cgroup is reclaimed");
    }

    @Test
    void orphanedLiveCgroupWithoutKernelKillControlFailsClosed(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve(deadOwner().markedName("minos-live")));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root));

        assertTrue(failure.getMessage().contains("cgroup.kill"));
        assertTrue(Files.exists(stale), "unverified live containment residue must remain visible");
    }

    @Test
    void aCgroupOwnedByAnotherLiveMinosProcessIsLeftIntact(@TempDir Path root) throws Exception {
        // Another live MINOS instance: a foreign instance token stamped with a PID that is alive (our own).
        Mark otherInstance = Mark.of(ProcessHandle.current(), "0f1e2d3c");
        Path live = Files.createDirectory(root.resolve(otherInstance.markedName("minos-provider-other")));
        Files.writeString(live.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(root),
                "a job owned by another live MINOS process must not be killed by the sweep");

        assertTrue(Files.exists(live), "the cgroup of a live MINOS instance must remain untouched");
        assertEquals("424242\n", Files.readString(live.resolve(LinuxCgroupJob.PROCS_FILE), StandardCharsets.UTF_8));
        assertEquals(List.of(String.valueOf(live.getFileName())), sweep.leftIntact());
    }

    @Test
    void aCgroupOwnedByThisJvmIsLeftIntact(@TempDir Path root) throws Exception {
        Path own = Files.createDirectory(root.resolve(CgroupJobOwnership.CURRENT.markedName("minos-provider-own")));
        Files.writeString(own.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(root));

        assertTrue(Files.exists(own), "our own job must never be reclaimed by our own sweep");
    }

    @Test
    void anUnmarkedCgroupWithLiveProcessesIsLeftIntact(@TempDir Path root) throws Exception {
        Path legacy = Files.createDirectory(root.resolve("minos-legacy-live"));
        Files.writeString(legacy.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        LinuxCgroupJob.StaleSweep sweep = assertDoesNotThrow(() -> LinuxCgroupJob.reclaimStaleJobs(root),
                "an unmarked cgroup with processes has an unknown owner and must not be killed");

        assertTrue(Files.exists(legacy));
        assertEquals(List.of("minos-legacy-live"), sweep.leftIntact(), "the unexplained residue must be reported");
        assertEquals(List.of(), sweep.reclaimed());
    }

    /** A mark whose owner PID designates no live process on this host. */
    private static Mark deadOwner() {
        for (long pid = 2_147_000_001L; pid > 1L; pid -= 7_919L) {
            if (ProcessHandle.of(pid).isEmpty()) return new Mark(pid, 1L, "0dead0aa");
        }
        throw new AssertionError("no free pid found");
    }

    @Test
    void unreadableStaleMembershipFailsClosed(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-unknown"));
        Files.createDirectory(stale.resolve(LinuxCgroupJob.PROCS_FILE));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root));

        assertTrue(failure.getMessage().contains("unable to read cgroup membership"));
        assertTrue(Files.exists(stale), "unknown membership must not be reported as reclaimed");
    }

    @Test
    void nonMinosDirectoriesAreNeverTouched(@TempDir Path root) throws Exception {
        Path foreign = Files.createDirectory(root.resolve("foreign-job"));
        Files.writeString(foreign.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        LinuxCgroupJob.reclaimStaleJobs(root);

        assertTrue(Files.exists(foreign));
    }
}
