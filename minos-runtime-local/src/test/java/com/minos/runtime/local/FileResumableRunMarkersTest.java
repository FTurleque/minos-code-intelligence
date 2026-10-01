package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : marqueur durable runs/<runId>/.resumable (ADR 0039 §5). */
class FileResumableRunMarkersTest {

    @Test
    void markWritesTheMarkerInsideTheRunDirectoryAndUnmarkRemovesIt(@TempDir Path home) throws Exception {
        UUID runId = UUID.randomUUID();
        Path runDirectory = Files.createDirectories(home.resolve("runs").resolve(runId.toString()).resolve("scip-java"));
        Files.writeString(runDirectory.resolve("index.scip"), "index");
        FileResumableRunMarkers markers = new FileResumableRunMarkers(home);

        markers.mark(runId);
        Path marker = home.resolve("runs").resolve(runId.toString()).resolve(FileResumableRunMarkers.MARKER_FILE_NAME);
        assertTrue(Files.isRegularFile(marker));
        assertTrue(Files.readString(marker).contains("runId=" + runId));
        assertEquals(".resumable", FileResumableRunMarkers.MARKER_FILE_NAME);

        markers.mark(runId);
        assertTrue(Files.isRegularFile(marker), "marking twice is idempotent");

        markers.unmark(runId);
        assertFalse(Files.exists(marker));
        assertTrue(Files.isRegularFile(runDirectory.resolve("index.scip")), "unmark never touches artifacts");

        markers.unmark(runId);
        assertFalse(Files.exists(marker), "unmarking an unmarked run is a no-op");
    }

    @Test
    void markCreatesTheRunDirectoryWhenTheRunLeftNoArtifactYet(@TempDir Path home) throws Exception {
        UUID runId = UUID.randomUUID();

        new FileResumableRunMarkers(home).mark(runId);

        assertTrue(Files.isRegularFile(home.resolve("runs").resolve(runId.toString()).resolve(".resumable")));
    }

    @Test
    void unmarkOfARunThatWroteNothingLeavesNoEmptyRunDirectoryBehind(@TempDir Path home) throws Exception {
        // V-L1-03: the run is held before its first provider, which creates the directory; a run that
        // ends without any artifact must not leave an empty directory counted against the budget.
        UUID runId = UUID.randomUUID();
        FileResumableRunMarkers markers = new FileResumableRunMarkers(home);
        markers.mark(runId);
        Path runDirectory = home.resolve("runs").resolve(runId.toString());
        assertTrue(Files.isDirectory(runDirectory));

        markers.unmark(runId);

        assertFalse(Files.exists(runDirectory), "an empty run directory goes away with its marker");
    }

    @Test
    void unmarkNeverRemovesARunDirectoryThatHoldsArtifacts(@TempDir Path home) throws Exception {
        UUID runId = UUID.randomUUID();
        FileResumableRunMarkers markers = new FileResumableRunMarkers(home);
        markers.mark(runId);
        Path runDirectory = home.resolve("runs").resolve(runId.toString());
        Files.writeString(runDirectory.resolve("index.scip"), "index");

        markers.unmark(runId);

        assertTrue(Files.isRegularFile(runDirectory.resolve("index.scip")));
    }

    @Test
    void unmarkOfARunWhoseDirectoryIsGoneIsANoOp(@TempDir Path home) throws Exception {
        new FileResumableRunMarkers(home).unmark(UUID.randomUUID());

        assertFalse(Files.exists(home.resolve("runs")) && Files.list(home.resolve("runs")).findAny().isPresent());
    }
}
