package com.minos.runtime.local;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunDirectoryRetentionTest {

    @Test
    void pruneBoundsOldRunsAndNeverDeletesProtectedRun(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path first = run(runs, "first", 8);
        Path second = run(runs, "second", 8);
        Path current = run(runs, "current", 64);
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        Files.setLastModifiedTime(first, FileTime.from(now.minus(Duration.ofHours(3))));
        Files.setLastModifiedTime(second, FileTime.from(now.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(current, FileTime.from(now));

        RunDirectoryRetention.prune(
                runs,
                current,
                new RunDirectoryRetention.Policy(1, 16, Duration.ofDays(7)),
                now
        );

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(current), "the active run must never be reclaimed");
    }

    @Test
    void pruneDeletesExpiredRunsEvenWhenCountAndBytesAreBelowLimits(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path expired = run(runs, "expired", 1);
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        Files.setLastModifiedTime(expired, FileTime.from(now.minus(Duration.ofDays(8))));

        RunDirectoryRetention.prune(
                runs,
                null,
                new RunDirectoryRetention.Policy(10, 1024, Duration.ofDays(7)),
                now
        );

        assertFalse(Files.exists(expired));
    }

    @Test
    void anOversizedHostileRunIsReclaimedInsteadOfBlockingEveryFutureIndexation(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path hostile = runs.resolve("hostile");
        Files.createDirectories(hostile);
        for (int index = 0; index < 40; index++) {
            Files.writeString(hostile.resolve("entry-" + index), "x");
        }
        Path healthy = run(runs, "healthy", 8);
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        Files.setLastModifiedTime(hostile, FileTime.from(now));
        Files.setLastModifiedTime(healthy, FileTime.from(now));

        RunDirectoryRetention.prune(
                runs,
                healthy,
                new RunDirectoryRetention.Policy(16, 4L * 1024L * 1024L, Duration.ofDays(7)),
                now,
                new RunDirectoryRetention.Budgets(4L, 4_096L, 250_000L));

        assertFalse(Files.exists(hostile), "a run exceeding the scan budget must be reclaimed, not fatal");
        assertTrue(Files.exists(healthy), "a healthy run must survive an adjacent hostile run");
    }

    @Test
    void aRunTooLargeToDeleteInOnePassIsQuarantinedAndDrainedLater(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path hostile = runs.resolve("hostile");
        Files.createDirectories(hostile);
        for (int index = 0; index < 40; index++) {
            Files.writeString(hostile.resolve("entry-" + index), "x");
        }
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        Files.setLastModifiedTime(hostile, FileTime.from(now.minus(Duration.ofDays(30))));

        RunDirectoryRetention.prune(
                runs,
                null,
                new RunDirectoryRetention.Policy(16, 4L * 1024L * 1024L, Duration.ofDays(7)),
                now,
                new RunDirectoryRetention.Budgets(1_000_000L, 4_096L, 5L));

        assertFalse(Files.exists(hostile), "the hostile run must leave the active run namespace immediately");
        Path quarantine = runs.resolve(RunDirectoryRetention.QUARANTINE_DIRECTORY);
        assertTrue(Files.isDirectory(quarantine), "the residue must be quarantined, never left in place");

        for (int pass = 0; pass < 20; pass++) {
            RunDirectoryRetention.prune(
                    runs,
                    null,
                    new RunDirectoryRetention.Policy(16, 4L * 1024L * 1024L, Duration.ofDays(7)),
                    now,
                    new RunDirectoryRetention.Budgets(1_000_000L, 4_096L, 20L));
        }
        try (var children = Files.list(quarantine)) {
            assertTrue(children.findAny().isEmpty(), "bounded passes must eventually drain the quarantine");
        }
    }

    @Test
    void pruneNeverDeletesOutsideTheRunsRoot(@TempDir Path home) throws Exception {
        Path runs = Files.createDirectories(home.resolve("runs"));
        Path outside = Files.createDirectories(home.resolve("outside"));

        assertThrows(IOException.class, () -> RunDirectoryRetention.deleteTree(runs, outside));
        assertThrows(IOException.class, () -> RunDirectoryRetention.deleteTree(runs, runs));
        assertTrue(Files.exists(outside));
    }

    @Test
    void deleteTreeQuarantinesAWindowsJunctionInsteadOfWalkingThroughToItsTarget(@TempDir Path home) throws Exception {
        Assumptions.assumeTrue(CommandLocator.isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path runs = Files.createDirectories(home.resolve("runs"));
        Path outside = Files.createDirectories(home.resolve("outside"));
        Path evidence = Files.writeString(outside.resolve("evidence.txt"), "preserved");
        Path hostileRun = run(runs, "hostile", 4);
        Path junction = hostileRun.resolve("outside-junction");
        createJunction(junction, outside);

        RunDirectoryRetention.deleteTree(runs, hostileRun);

        assertFalse(Files.exists(hostileRun));
        assertFalse(Files.exists(junction, LinkOption.NOFOLLOW_LINKS), "the junction entry itself must be removed");
        assertTrue(Files.isRegularFile(evidence), "reclaiming a hostile run must never delete anything at a junction's target");
    }

    @Test
    void resumableRunIsReclaimedLastAndOnlyWhenTheBudgetStillRequiresIt(@TempDir Path home) throws Exception {
        // ADR 0039 §5 / R1-7: 17 runs, the oldest one marked .resumable; the count budget (16) is
        // satisfied by reclaiming the oldest UNMARKED run, so the marked run survives even though it
        // is the oldest by mtime.
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Path marked = run(runs, "marked", 8);
        Path marker = Files.writeString(marked.resolve(FileResumableRunMarkers.MARKER_FILE_NAME), "runId=marked\n");
        Files.setLastModifiedTime(marker, FileTime.from(now.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(marked, FileTime.from(now.minus(Duration.ofHours(6))));
        Path oldestUnmarked = run(runs, "oldest-unmarked", 8);
        Files.setLastModifiedTime(oldestUnmarked, FileTime.from(now.minus(Duration.ofHours(5))));
        for (int index = 0; index < 15; index++) {
            Path other = run(runs, "run-" + index, 8);
            Files.setLastModifiedTime(other, FileTime.from(now.minus(Duration.ofMinutes(60 - index))));
        }

        RunDirectoryRetention.prune(runs, null,
                new RunDirectoryRetention.Policy(16, 1024L * 1024L, Duration.ofDays(7), Duration.ofHours(24)), now);

        assertFalse(Files.exists(oldestUnmarked), "the budget is met by the oldest unmarked run");
        assertTrue(Files.exists(marked), "a resumable run is reclaimed last");
        for (int index = 0; index < 15; index++) assertTrue(Files.exists(runs.resolve("run-" + index)));
    }

    @Test
    void resumableRunIsReclaimedWhenTheBudgetStillRequiresItAfterEveryUnmarkedRun(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Path marked = run(runs, "marked", 8);
        Files.writeString(marked.resolve(FileResumableRunMarkers.MARKER_FILE_NAME), "runId=marked\n");
        Path fresh = run(runs, "fresh", 8);
        Files.setLastModifiedTime(marked, FileTime.from(now.minus(Duration.ofHours(1))));
        Files.setLastModifiedTime(fresh, FileTime.from(now));

        RunDirectoryRetention.prune(runs, fresh,
                new RunDirectoryRetention.Policy(1, 4L, Duration.ofDays(7), Duration.ofHours(24)), now);

        assertFalse(Files.exists(marked), "over the byte budget with nothing else to reclaim, the marked run goes");
        assertTrue(Files.exists(fresh), "the current run stays protected");
    }

    @Test
    void markingARunNeverShortensItsLifetimeBelowThatOfAnOrdinaryRun(@TempDir Path home) throws Exception {
        // R5: a marker used to expire after the 24 h resume TTL where an ordinary run lives 7 days,
        // so marking an interrupted run divided its lifetime by seven. 25 h old, still marked: it stays.
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Path marked = markedRun(runs, "marked", now.minus(Duration.ofHours(25)), now.minus(Duration.ofHours(25)));
        Path ordinary = run(runs, "ordinary", 8);
        Files.setLastModifiedTime(ordinary, FileTime.from(now.minus(Duration.ofHours(25))));

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, now);

        assertTrue(Files.exists(marked), "a marker must lengthen the lifetime, never shorten it");
        assertTrue(Files.exists(ordinary));
        assertEquals(Duration.ofDays(7), RunDirectoryRetention.DEFAULT.lifetime(false));
        assertEquals(Duration.ofDays(7), RunDirectoryRetention.DEFAULT.lifetime(true));
    }

    @Test
    void theMarkerLengthensTheLifetimeWhenTheResumeTtlExceedsTheMaximumAge(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Path marked = markedRun(runs, "marked", now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(5)));
        Path ordinary = run(runs, "ordinary", 8);
        Files.setLastModifiedTime(ordinary, FileTime.from(now.minus(Duration.ofHours(5))));
        RunDirectoryRetention.Policy policy =
                new RunDirectoryRetention.Policy(16, 1024L * 1024L, Duration.ofHours(2), Duration.ofHours(24));

        RunDirectoryRetention.prune(runs, null, policy, now);

        assertFalse(Files.exists(ordinary), "an ordinary run is reclaimed after the maximum age");
        assertTrue(Files.exists(marked), "a marked run is kept for the longer of the maximum age and the resume TTL");
        assertEquals(Duration.ofHours(24), policy.lifetime(true));
        assertEquals(Duration.ofHours(2), policy.lifetime(false));
        assertEquals(Duration.ofHours(24), RunDirectoryRetention.DEFAULT.resumeTtl(),
                "aligned by hand on the resume planner's TTL (ADR 0039, deviation f)");
    }

    @Test
    void aMarkedRunAlwaysExpiresAtTheExplicitUpperBound(@TempDir Path home) throws Exception {
        // R5 disk-leak guard: the marker protects, it never pins runs/ forever.
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        RunDirectoryRetention.Policy policy = RunDirectoryRetention.DEFAULT;
        Duration bound = policy.lifetime(true);
        Path expired = markedRun(runs, "expired", now.minus(bound).minusSeconds(1), now.minus(bound).minusSeconds(1));
        Path justInside = markedRun(runs, "just-inside", now.minus(bound).plusSeconds(1), now.minus(bound).plusSeconds(1));

        RunDirectoryRetention.prune(runs, null, policy, now,
                new RunDirectoryRetention.Budgets(1_000_000L, 4_096L, 250_000L));
        assertFalse(Files.exists(expired), "past the upper bound a marked run is reclaimed");
        assertTrue(Files.exists(justInside), "inside the bound it is kept");

        RunDirectoryRetention.prune(runs, null, policy, now.plusSeconds(2),
                new RunDirectoryRetention.Budgets(1_000_000L, 4_096L, 250_000L));
        assertFalse(Files.exists(justInside), "and it expires as soon as the bound is crossed");
    }

    @Test
    void aMarkedRunAlsoExpiresAtTheUpperBoundWhenTheScanIsTruncated(@TempDir Path home) throws Exception {
        // V-L1-02: the scan really is truncated here (one entry observed per pass, two runs present).
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Duration bound = RunDirectoryRetention.DEFAULT.lifetime(true);
        Instant tooOld = now.minus(bound).minusSeconds(1);
        markedRun(runs, "expired-a", tooOld, tooOld);
        markedRun(runs, "expired-b", tooOld, tooOld);
        RunDirectoryRetention.Budgets oneEntryPerPass = new RunDirectoryRetention.Budgets(1_000_000L, 1L, 250_000L);

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, now, oneEntryPerPass);
        try (var children = Files.list(runs)) {
            assertEquals(1L, children.count(), "the truncated pass reclaims the expired run it observed, not the other");
        }

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, now, oneEntryPerPass);
        try (var children = Files.list(runs)) {
            assertEquals(0L, children.count(), "the next pass finishes: a marker never pins runs/");
        }
    }

    @Test
    void aMarkerDatedInTheFutureNeverPinsARunBeyondOneLifetimeFromWhenItWasSeen(@TempDir Path home) throws Exception {
        // V-L1-01: a clock that jumped forward while the marker was written must not keep the run for the
        // jump plus seven days. The first pass that sees the impossible date re-dates it to now.
        Path runs = home.resolve("runs");
        Instant seen = Instant.parse("2026-09-26T12:00:00Z");
        Duration bound = RunDirectoryRetention.DEFAULT.lifetime(true);
        Path pinned = markedRun(runs, "future-marker", seen.plus(Duration.ofDays(365)), seen.minus(Duration.ofDays(30)));

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, seen);
        assertTrue(Files.exists(pinned), "the pass that sees the impossible date cannot tell the age: it keeps the run");

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, seen.plus(bound).minusSeconds(1));
        assertTrue(Files.exists(pinned), "still inside one lifetime from when the date was seen");
        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, seen.plus(bound).plusSeconds(1));
        assertFalse(Files.exists(pinned), "and gone one lifetime after it was seen, not a year later");
    }

    @Test
    void aRunDirectoryDatedInTheFutureIsRedatedAndExpiresLikeAnyOther(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Instant seen = Instant.parse("2026-09-26T12:00:00Z");
        Path pinned = markedRun(runs, "future-run", seen.plus(Duration.ofDays(365)), seen.plus(Duration.ofDays(365)));

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, seen);
        assertTrue(Files.exists(pinned));
        assertFalse(Files.getLastModifiedTime(pinned).toInstant().isAfter(seen), "the directory date was re-dated");
        assertFalse(Files.getLastModifiedTime(pinned.resolve(FileResumableRunMarkers.MARKER_FILE_NAME))
                .toInstant().isAfter(seen), "and so was the marker's");

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT,
                seen.plus(RunDirectoryRetention.DEFAULT.lifetime(true)).plusSeconds(1));
        assertFalse(Files.exists(pinned));
    }

    @Test
    void aTruncatedScanNeverReclaimsARunOfferedForResume(@TempDir Path home) throws Exception {
        // R5: scan.truncated() used to make overCount true for EVERY entry, .resumable included.
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        List<Path> marked = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            marked.add(markedRun(runs, "marked-" + index, now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1))));
        }

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, now,
                new RunDirectoryRetention.Budgets(1_000_000L, 3L, 250_000L));

        for (Path run : marked) assertTrue(Files.exists(run), "a marked run survives a truncated scan: " + run.getFileName());
    }

    @Test
    void aTruncatedScanStillReclaimsUnmarkedRunsItObserved(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        for (int index = 0; index < 6; index++) {
            Path run = run(runs, "ordinary-" + index, 8);
            Files.setLastModifiedTime(run, FileTime.from(now.minus(Duration.ofHours(1))));
        }

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, now,
                new RunDirectoryRetention.Budgets(1_000_000L, 3L, 250_000L));

        try (var children = Files.list(runs)) {
            assertEquals(3L, children.filter(Files::isDirectory).count(),
                    "the three observed unmarked runs are reclaimed, the three unobserved wait for the next pass");
        }
    }

    @Test
    void markedRunsRemainBoundedByTheCountBudget(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        for (int index = 0; index < 20; index++) {
            Instant marked = now.minus(Duration.ofMinutes(100 - index));
            markedRun(runs, "marked-%02d".formatted(index), marked, marked);
        }

        RunDirectoryRetention.prune(runs, null, RunDirectoryRetention.DEFAULT, now);

        try (var children = Files.list(runs)) {
            assertEquals(16L, children.count(), "the marker defers deletion, it never lifts the count budget");
        }
        for (int index = 0; index < 4; index++) {
            assertFalse(Files.exists(runs.resolve("marked-%02d".formatted(index))), "the oldest marked runs go first");
        }
    }

    @Test
    void anEntryThatVanishesOrIsUnreadableNeverDisqualifiesARunHeldByAMarker() {
        // R5: another indexation renames its temporaries while this one measures; a vanished entry is
        // not evidence of hostile residue, and a held run is never reclaimed first for a read failure.
        assertFalse(RunDirectoryRetention.unreadableEntryMakesRunReclaimable(
                new java.nio.file.NoSuchFileException("artifact.partial"), false));
        assertFalse(RunDirectoryRetention.unreadableEntryMakesRunReclaimable(new IOException("denied"), true));
        assertTrue(RunDirectoryRetention.unreadableEntryMakesRunReclaimable(new IOException("denied"), false),
                "an unreadable entry of an unmarked run is still hostile residue");
    }

    @Test
    void anInFlightRunOfAnotherIndexationSurvivesEveryConcurrentPruneWhileItIsBeingWritten(@TempDir Path home)
            throws Exception {
        // R5, second indexation: process B prunes under budget pressure and a truncated scan while
        // process A is still producing artifacts in its own run directory. A's marker (posed before its
        // first provider) is the only thing B can see. Synchronised on a barrier, repeated 50 times.
        FileResumableRunMarkers markers = new FileResumableRunMarkers(home);
        Path runs = home.resolve("runs");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < CONCURRENCY_ROUNDS; round++) {
                UUID runA = UUID.randomUUID();
                Path directoryA = Files.createDirectories(runs.resolve(runA.toString()));
                for (int decoy = 0; decoy < 5; decoy++) run(runs, "decoy-" + round + "-" + decoy, 4);
                markers.mark(runA);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> writer = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int file = 0; file < 20; file++) {
                        Files.writeString(directoryA.resolve("artifact-" + file), "scip");
                    }
                    return null;
                });
                Future<?> pruner = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int pass = 0; pass < 20; pass++) {
                        RunDirectoryRetention.prune(runs, null,
                                new RunDirectoryRetention.Policy(2, 1024L * 1024L, Duration.ofDays(7)), Instant.now(),
                                new RunDirectoryRetention.Budgets(1_000_000L, 2L, 250_000L));
                    }
                    return null;
                });
                writer.get(30, TimeUnit.SECONDS);
                pruner.get(30, TimeUnit.SECONDS);

                assertTrue(Files.isDirectory(directoryA), "round " + round + ": the in-flight run must survive");
                for (int file = 0; file < 20; file++) {
                    assertTrue(Files.exists(directoryA.resolve("artifact-" + file)), "round " + round + " file " + file);
                }
                markers.unmark(runA);
                RunDirectoryRetention.deleteTree(runs, directoryA);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static final int CONCURRENCY_ROUNDS = 50;

    /** A run directory held by a marker, dated explicitly (the marker is written after the artifacts). */
    private static Path markedRun(Path runs, String name, Instant markerTime, Instant directoryTime) throws Exception {
        Path directory = run(runs, name, 8);
        Path marker = Files.writeString(directory.resolve(FileResumableRunMarkers.MARKER_FILE_NAME), "runId=" + name + "\n");
        Files.setLastModifiedTime(marker, FileTime.from(markerTime));
        Files.setLastModifiedTime(directory, FileTime.from(directoryTime));
        return directory;
    }

    private static void createJunction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("mklink /J failed (exit=" + exit + "): " + output);
    }

    private static Path run(Path runs, String name, int bytes) throws Exception {
        Path directory = runs.resolve(name);
        Files.createDirectories(directory);
        Files.write(directory.resolve("payload.bin"), new byte[bytes]);
        return directory;
    }
}
