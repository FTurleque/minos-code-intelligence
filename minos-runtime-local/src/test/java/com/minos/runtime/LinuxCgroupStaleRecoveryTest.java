package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxCgroupStaleRecoveryTest {

    @Test
    void emptyStaleCgroupIsReclaimedWithoutRequiringKillControl(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-empty"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);

        LinuxCgroupJob.reclaimStaleJobs(root);

        assertFalse(Files.exists(stale), "an empty stale cgroup should be removed");
    }

    @Test
    void emptyStaleCgroupDeletionFailureDoesNotDisableContainment(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-empty-with-residue"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);
        Files.writeString(stale.resolve("unexpected-residue"), "x", StandardCharsets.UTF_8);

        LinuxCgroupJob.reclaimStaleJobs(root);

        assertTrue(Files.exists(stale), "empty-directory cleanup remains best effort");
    }

    @Test
    void liveStaleCgroupWithoutKernelKillControlFailsClosed(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-live"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LinuxCgroupJob.reclaimStaleJobs(root));

        assertTrue(failure.getMessage().contains("cgroup.kill"));
        assertTrue(Files.exists(stale), "unverified live containment residue must remain visible");
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
