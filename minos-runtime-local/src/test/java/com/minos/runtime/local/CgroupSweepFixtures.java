package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Mark;
import com.minos.runtime.local.CgroupJobOwnership.Namespaces;
import com.minos.runtime.local.CgroupJobOwnership.OwnerLookup;
import com.minos.runtime.local.CgroupJobOwnership.OwnerStatus;
import com.minos.runtime.local.LinuxCgroupJob.SweepContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared, host-independent evidence for the stale-cgroup tests: marks of a sweeping MINOS process and
 * of another one, a process table the test scripts, and a directory removal that behaves like the
 * kernel's (it removes a cgroup together with the pseudo-files a temporary directory cannot remove).
 */
final class CgroupSweepFixtures {

    /** The namespaces of the sweeping process and of the owner it sweeps: the same ones. */
    static final Namespaces NAMESPACES = new Namespaces(4_026_531_836L, 4_026_531_834L);
    /** The namespaces of a MINOS process living in another PID namespace. */
    static final Namespaces OTHER_PID_NAMESPACE = new Namespaces(4_026_532_901L, 4_026_531_834L);
    /** The namespaces of a MINOS process living in another time namespace. */
    static final Namespaces OTHER_TIME_NAMESPACE = new Namespaces(4_026_531_836L, 4_026_532_950L);

    /** The mark of the sweeping MINOS process. */
    static final Mark SELF = new Mark(4_242L, 1_234_567L, "0badcafe").withNamespaces(NAMESPACES);
    /** The mark of another MINOS process, in the same namespaces. */
    static final Mark OWNER = new Mark(7_777L, 2_345_678L, "deadbeef").withNamespaces(NAMESPACES);

    /** Removes a cgroup directory tree the way the kernel does: its files go with it. */
    static final LinuxCgroupJob.CgroupRemoval KERNEL_LIKE = CgroupSweepFixtures::removeTree;

    private CgroupSweepFixtures() {
    }

    /** A sweep by {@link #SELF} that reads the process table through {@code owners} and removes like the kernel. */
    static SweepContext context(OwnerLookup owners) {
        return new SweepContext(SELF, cgroup -> owners, KERNEL_LIKE, 4_096L, 2, 0L);
    }

    /** Same, with the removal of a temporary directory, which fails on a cgroup that holds pseudo-files. */
    static SweepContext contextWithPlainRemoval(OwnerLookup owners) {
        return new SweepContext(SELF, cgroup -> owners, LinuxCgroupJob.CgroupRemoval.KERNEL, 4_096L, 2, 0L);
    }

    /** A process table in which no process exists. */
    static OwnerLookup nobodyIsAlive() {
        return pid -> OwnerStatus.gone();
    }

    /** A process table in which {@code pid} is alive with the given kernel start ticks. */
    static OwnerLookup onlyAlive(long pid, long startTicks) {
        return candidate -> candidate == pid ? OwnerStatus.present(startTicks) : OwnerStatus.gone();
    }

    /** A process table that cannot be read. */
    static OwnerLookup unreadable(String why) {
        return pid -> OwnerStatus.unverifiable(why);
    }

    /** A cgroup directory as the kernel shows it: a membership list and a kill switch. */
    static Path cgroup(Path root, String name, String members) throws IOException {
        Path cgroup = Files.createDirectory(root.resolve(name));
        Files.writeString(cgroup.resolve(LinuxCgroupJob.PROCS_FILE), members, StandardCharsets.UTF_8);
        Files.writeString(cgroup.resolve("cgroup.kill"), "0", StandardCharsets.UTF_8);
        return cgroup;
    }

    /** What has been written to the kill switch of a cgroup: {@code "1"} when a kill was requested. */
    static String killSwitch(Path cgroup) throws IOException {
        return Files.readString(cgroup.resolve("cgroup.kill"), StandardCharsets.UTF_8);
    }

    static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> tree = Files.walk(directory)) {
            for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }
}
