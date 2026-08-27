package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingRunExecutorTest {

    @Test
    void awaitReadableRecoversFromAnArtifactThatBrieflyAppearsUnreadable(@TempDir Path temp) throws Exception {
        // A real-time antivirus scan can hold a transient handle on a just-written artifact for a
        // moment after the provider process that wrote it has already exited. This proves the
        // bounded retry survives exactly that window instead of failing the whole run on the first
        // (still momentarily unreadable) check.
        Path artifact = temp.resolve("index.scip");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            try {
                Files.writeString(artifact, "scip");
            } catch (IOException ignored) {
                // Test failure surfaces via the missing file below.
            }
        }, 150, TimeUnit.MILLISECONDS);
        try {
            assertTrue(IndexingRunExecutor.awaitReadable(artifact),
                    "the artifact must become visible within the retry window");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void awaitReadableFailsClosedWhenTheArtifactNeverAppears(@TempDir Path temp) {
        Path artifact = temp.resolve("never-written.scip");
        assertFalse(IndexingRunExecutor.awaitReadable(artifact));
    }
}
