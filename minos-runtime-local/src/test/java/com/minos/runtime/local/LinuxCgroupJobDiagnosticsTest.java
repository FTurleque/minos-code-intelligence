package com.minos.runtime.local;

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

import static com.minos.runtime.local.CgroupSweepFixtures.KERNEL_LIKE;
import static com.minos.runtime.local.CgroupSweepFixtures.OWNER;
import static com.minos.runtime.local.CgroupSweepFixtures.SELF;
import static com.minos.runtime.local.CgroupSweepFixtures.cgroup;
import static com.minos.runtime.local.CgroupSweepFixtures.contextWithPlainRemoval;
import static com.minos.runtime.local.CgroupSweepFixtures.context;
import static com.minos.runtime.local.CgroupSweepFixtures.nobodyIsAlive;
import static com.minos.runtime.local.CgroupSweepFixtures.onlyAlive;
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
        String otherInstance = OWNER.markedName("minos-provider-other");
        String legacyMark = "minos-provider-legacy.own-" + OWNER.pid() + "-1700000160000-0e1d2c3b";
        for (String name : List.of(unmarked, otherInstance, legacyMark)) {
            cgroup(root, name, "424242\n");
        }
        cgroup(root, "minos-empty-unmarked", "");

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(
                root, context(onlyAlive(OWNER.pid(), OWNER.start()))));

        assertEquals(3, sweep.residues().size(), String.valueOf(sweep));
        assertEquals(List.of("minos-empty-unmarked"), sweep.reclaimed());
        List<LogRecord> reports = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .toList();
        assertEquals(1, reports.size(), "exactly one aggregated report per qualification: " + messages(records));
        String warning = reports.getFirst().getMessage();
        for (LinuxCgroupJob.Residue residue : sweep.residues()) {
            assertTrue(warning.contains(residue.name() + " (" + residue.reason() + ")"),
                    "the WARNING must name each residue with its reason: " + warning);
        }
        assertTrue(warning.contains("left 3 cgroup(s) intact"), warning);
        assertFalse(warning.contains("minos-empty-unmarked"), "a reclaimed cgroup is not a residue: " + warning);
        assertNoAbsolutePath(records, root);
    }

    @Test
    void aSweepThatLeavesNothingBehindReportsNoResidue(@TempDir Path root) throws Exception {
        cgroup(root, "minos-empty-unmarked", "");

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(
                root, context(nobodyIsAlive())));

        assertEquals(List.of("minos-empty-unmarked"), sweep.reclaimed());
        assertEquals(List.of(), sweep.residues());
        assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
                "no residue, no warning: " + messages(records));
    }

    /** A sweep that kills or removes something says so, by name and never by path: the operator sees what it did. */
    @Test
    void whatASweepReclaimsIsJournaledAtInfoWithoutAnAbsolutePath(@TempDir Path root) throws Exception {
        cgroup(root, "minos-empty-unmarked", "");
        cgroup(root, OWNER.markedName("minos-orphan"), "");

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(
                root, context(nobodyIsAlive())));

        assertEquals(2, sweep.reclaimed().size(), sweep.toString());
        List<LogRecord> summaries = records.stream().filter(r -> r.getLevel() == Level.INFO).toList();
        assertEquals(1, summaries.size(), "one summary per qualification: " + messages(records));
        assertTrue(summaries.getFirst().getMessage().contains("reclaimed 2 stale cgroup(s)"), messages(summaries));
        for (String name : sweep.reclaimed()) {
            assertTrue(summaries.getFirst().getMessage().contains(name), messages(summaries));
        }
        assertNoAbsolutePath(records, root);
    }

    @Test
    void aDeletionFailureIsReportedAsAResidueByNameWithoutAnAbsolutePath(@TempDir Path root) throws Exception {
        Path stale = Files.createDirectory(root.resolve("minos-empty-with-residue"));
        Files.writeString(stale.resolve(LinuxCgroupJob.PROCS_FILE), "", StandardCharsets.UTF_8);
        Files.writeString(stale.resolve("unexpected-residue"), "x", StandardCharsets.UTF_8);

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records, () -> LinuxCgroupJob.reclaimAndReportStaleJobs(
                root, contextWithPlainRemoval(nobodyIsAlive())));

        assertEquals(List.of(), sweep.reclaimed(), "what could not be removed was not reclaimed");
        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertEquals(1, warnings.size(), "one aggregated report, no second per-cgroup line: " + messages(records));
        assertTrue(warnings.getFirst().getMessage().contains("minos-empty-with-residue")
                        && warnings.getFirst().getMessage().contains("could not remove"),
                "the failed removal is reported with the cgroup name: " + messages(records));
        assertTrue(warnings.getFirst().getMessage().contains("left 1 cgroup(s) intact"), messages(warnings));
        assertNoAbsolutePath(records, root);
    }

    /** A sweep cut short by its bound names how many entries it never looked at: they may be residues too. */
    @Test
    void theWarningCountsTheEntriesTheBoundedSweepDidNotExamine(@TempDir Path root) throws Exception {
        for (int index = 0; index < 5; index++) cgroup(root, "minos-empty-" + index, "");
        LinuxCgroupJob.SweepContext bounded = new LinuxCgroupJob.SweepContext(
                SELF, path -> nobodyIsAlive(), KERNEL_LIKE, 3L, 2, 0L);

        List<LogRecord> records = new ArrayList<>();
        LinuxCgroupJob.StaleSweep sweep = capture(records,
                () -> LinuxCgroupJob.reclaimAndReportStaleJobs(root, bounded));

        assertEquals(2, sweep.notExamined());
        assertEquals(List.of(), sweep.residues(), "nothing left in place among what was examined");
        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertEquals(1, warnings.size(), "an incomplete sweep is reported even without a residue: " + messages(records));
        assertTrue(warnings.getFirst().getMessage().contains("2 entries were not examined"), messages(warnings));
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
