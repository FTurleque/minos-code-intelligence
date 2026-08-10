package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static Path run(Path runs, String name, int bytes) throws Exception {
        Path directory = runs.resolve(name);
        Files.createDirectories(directory);
        Files.write(directory.resolve("payload.bin"), new byte[bytes]);
        return directory;
    }
}
