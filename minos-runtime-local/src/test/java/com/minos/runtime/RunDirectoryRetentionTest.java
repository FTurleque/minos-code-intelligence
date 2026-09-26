package com.minos.runtime;

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
    void resumableRunOlderThanTheResumeTtlIsReclaimedEvenUnderBudget(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Path expired = run(runs, "expired", 8);
        Path expiredMarker = Files.writeString(expired.resolve(FileResumableRunMarkers.MARKER_FILE_NAME), "runId=expired\n");
        Files.setLastModifiedTime(expiredMarker, FileTime.from(now.minus(Duration.ofHours(25))));
        // The sidecar/marker writes bumped the directory mtime recently (R1-7): the marker's age rules.
        Files.setLastModifiedTime(expired, FileTime.from(now));
        Path live = run(runs, "live", 8);
        Path liveMarker = Files.writeString(live.resolve(FileResumableRunMarkers.MARKER_FILE_NAME), "runId=live\n");
        Files.setLastModifiedTime(liveMarker, FileTime.from(now.minus(Duration.ofHours(23))));
        Files.setLastModifiedTime(live, FileTime.from(now.minus(Duration.ofDays(8))));

        RunDirectoryRetention.prune(runs, null,
                new RunDirectoryRetention.Policy(16, 1024L * 1024L, Duration.ofDays(7), Duration.ofHours(24)), now);

        assertFalse(Files.exists(expired), "a marker older than the resume TTL no longer protects the run");
        assertTrue(Files.exists(live), "a live marker protects the run even past the general max age");
        assertEquals(Duration.ofHours(24), RunDirectoryRetention.DEFAULT.resumeTtl());
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
