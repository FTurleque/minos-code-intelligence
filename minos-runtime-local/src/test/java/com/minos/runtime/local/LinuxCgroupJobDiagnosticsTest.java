package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Mark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3: what the cgroup qualification tells the operator. Every residue the stale sweep leaves intact
 * is reported, once per qualification, in a single WARNING that names each cgroup relative to the
 * delegated root with the reason of the decision; no journal entry of {@link LinuxCgroupJob} ever
 * carries an absolute path, in its message or in an attached failure.
 *
 * <p>Host-independent: the sweep, the probe and the qualification run on a temporary directory
 * standing for the delegated root.</p>
 */
class LinuxCgroupJobDiagnosticsTest {

    @Test
    void everyResidueLeftIntactIsReportedInOneWarningPerQualification(@TempDir Path root) throws Exception {
        String unmarked = "minos-legacy-live";
        String otherInstance = Mark.of(ProcessHandle.current(), "0f1e2d3c").markedName("minos-provider-other");
        long pid = ProcessHandle.current().pid();
        long start = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        String legacyMark = "minos-provider-legacy.own-" + pid + "-" + (start + 60_000L) + "-0e1d2c3b";
        for (String name : List.of(unmarked, otherInstance, legacyMark)) {
            populated(root, name);
        }
        Path empty = Files.createDirectory(root.resolve("minos-empty-unmarked"));
        Files.writeString(empty.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(root));

        assertEquals(3, sweep.residues().size(), String.valueOf(sweep));
        assertEquals(List.of("minos-empty-unmarked"), sweep.reclaimed());
        // A temporary directory is never "already empty" for rmdir: its reclamation logs its own deletion
        // failure, which is not a residue report.
        List<LogRecord> reports = records.stream()
                .filter(r -> sweep.leftIntact().stream().anyMatch(r.getMessage()::contains))
                .toList();
        assertEquals(1, reports.size(), "exactly one aggregated report per qualification: " + messages(records));
        assertEquals(Level.WARNING, reports.getFirst().getLevel(), messages(reports));
        String warning = reports.getFirst().getMessage();
        for (LinuxCgroupJob.Residue residue : sweep.residues()) {
            assertTrue(warning.contains(residue.name() + " (" + residue.reason() + ")"),
                    "the WARNING must name each residue with its reason: " + warning);
        }
        assertTrue(warning.contains("3 "), warning);
        assertFalse(warning.contains("minos-empty-unmarked"), "a reclaimed cgroup is not a residue: " + warning);
        assertNoAbsolutePath(records, root);
    }

    @Test
    void aSweepThatLeavesNothingBehindReportsNothing(@TempDir Path root) throws Exception {
        Path empty = Files.createDirectory(root.resolve("minos-empty-unmarked"));
        Files.writeString(empty.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(root));

        assertEquals(List.of("minos-empty-unmarked"), sweep.reclaimed());
        assertEquals(List.of(), sweep.residues());
        assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                        && !r.getMessage().contains("could not remove")),
                "no residue, no report: " + messages(records));
    }

    @Test
    void aDeletionFailureIsReportedByNameWithoutAnAbsolutePath(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-empty-with-residue"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);
        Files.writeString(stale.resolve("unexpected-residue"), "x", StandardCharsets.UTF_8);

        List<LogRecord> records = new ArrayList<>();
        capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(root));

        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                        && r.getMessage().contains("could not remove")
                        && r.getMessage().contains("minos-empty-with-residue")),
                "the deletion failure is reported with the cgroup name: " + messages(records));
        assertNoAbsolutePath(records, root);
    }

    @Test
    void aProbeFailureIsReportedWithoutAnAbsolutePath(@TempDir Path root) throws Exception {
        List<LogRecord> records = new ArrayList<>();

        boolean qualified = capture(records, () -> LinuxCgroupJob.probe(root));

        assertFalse(qualified, "a plain directory has no cgroup.kill: the probe must fail");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                        && r.getMessage().contains("probe failed") && r.getMessage().contains("cgroup.kill")),
                "the probe failure keeps its diagnostic: " + messages(records));
        assertNoAbsolutePath(records, root);
    }

    @Test
    void aRejectedRootIsReportedWithoutAnAbsolutePath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("cgroup.controllers"), "cpuset cpu io memory pids\n", StandardCharsets.UTF_8);
        List<LogRecord> missingControl = new ArrayList<>();

        boolean withoutSubtreeControl = capture(missingControl, () -> LinuxCgroupJob.qualifyRoot(root));

        assertFalse(withoutSubtreeControl);
        assertTrue(missingControl.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                        && r.getMessage().contains("rejected") && r.getMessage().contains("cgroup.subtree_control")),
                messages(missingControl));
        assertNoAbsolutePath(missingControl, root);

        // A subtree_control that does not take the controllers is rejected with its own diagnostic.
        Files.writeString(root.resolve("cgroup.subtree_control"), "\n", StandardCharsets.UTF_8);
        List<LogRecord> notApplied = new ArrayList<>();

        boolean withoutControllers = capture(notApplied, () -> LinuxCgroupJob.qualifyRoot(root));

        assertFalse(withoutControllers);
        assertTrue(notApplied.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                        && r.getMessage().contains("does not expose memory/pids/cpu")),
                messages(notApplied));
        assertNoAbsolutePath(notApplied, root);
    }

    private static void populated(Path root, String name) throws Exception {
        Path cgroup = Files.createDirectory(root.resolve(name));
        Files.writeString(cgroup.resolve(LinuxCgroupJob.PROCS_FILE), "424242\n", StandardCharsets.UTF_8);
    }

    /** Fails when a record, or any failure attached to it (causes, suppressed), mentions an absolute path. */
    static void assertNoAbsolutePath(List<LogRecord> records, Path root) {
        for (LogRecord record : records) {
            assertPathFree(record.getMessage(), root);
            assertPathFree(record.getThrown(), root);
        }
    }

    static void assertPathFree(Throwable failure, Path root) {
        if (failure == null) return;
        assertPathFree(failure.getMessage(), root);
        assertPathFree(failure.toString(), root);
        assertPathFree(failure.getCause(), root);
        for (Throwable suppressed : failure.getSuppressed()) assertPathFree(suppressed, root);
    }

    static void assertPathFree(String text, Path root) {
        if (text == null) return;
        Path absolute = root.toAbsolutePath().normalize();
        for (Path ancestor = absolute; ancestor != null && ancestor.getNameCount() > 0; ancestor = ancestor.getParent()) {
            assertFalse(text.contains(ancestor.toString()), "absolute path " + ancestor + " leaked into: " + text);
        }
        assertFalse(text.contains(LinuxCgroupJob.CGROUP_MOUNT.toString()), "cgroup mount leaked into: " + text);
        assertFalse(text.contains("/sys/fs/cgroup"), "cgroup mount leaked into: " + text);
    }

    private static String messages(List<LogRecord> records) {
        return records.stream().map(r -> r.getLevel() + " " + r.getMessage()
                + (r.getThrown() == null ? "" : " [" + r.getThrown() + "]")).toList().toString();
    }

    @FunctionalInterface
    interface Action<T> {
        T run() throws Exception;
    }

    static <T> T capture(List<LogRecord> records, Action<T> action) {
        Logger logger = Logger.getLogger(LinuxCgroupJob.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            return assertDoesNotThrow(action::run);
        } finally {
            logger.removeHandler(handler);
        }
    }
}
