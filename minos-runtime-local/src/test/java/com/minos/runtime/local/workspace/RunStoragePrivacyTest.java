package com.minos.runtime.local.workspace;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.PrivateLocalStorage.Privacy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S5: everything MINOS keeps for a run or a cached bundle is owner-only. These directories and files
 * hold a copy of, or an index derived from, the user's code: Java defaults would leave them readable
 * by other local users. This class covers the runs root and the resumable marker.
 */
class RunStoragePrivacyTest {

    private static void assertPrivate(Path path) throws IOException {
        assertEquals(Privacy.ENFORCED, PrivateLocalStorage.privacyOf(path), "not owner-only: " + path.getFileName());
    }

    @Test
    void theRunsRootAndItsQuarantineAreOwnerOnly(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path stale = Files.createDirectories(runs.resolve("stale-run"));
        Files.writeString(stale.resolve("index.scip"), "x");
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofDays(30))));

        RunDirectoryRetention.prune(runs, null, new RunDirectoryRetention.Policy(10, 1024, Duration.ofDays(7)), now);

        assertPrivate(runs);
    }

    @Test
    void theResumableMarkerAndItsRunDirectoryAreOwnerOnly(@TempDir Path home) throws Exception {
        UUID runId = UUID.randomUUID();

        new FileResumableRunMarkers(home).mark(runId);

        Path runDirectory = home.resolve("runs").resolve(runId.toString());
        assertPrivate(runDirectory);
        assertPrivate(runDirectory.resolve(FileResumableRunMarkers.MARKER_FILE_NAME));
    }
}
