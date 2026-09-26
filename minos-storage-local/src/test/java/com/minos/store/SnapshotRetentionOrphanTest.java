package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 4 : rétention des snapshots préparés orphelins (ADR 0039 §4). */
class SnapshotRetentionOrphanTest {

    @Test
    void deletesOnlyPreparedSnapshotTemporariesOlderThanTheMaxAge(@TempDir Path root) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(root);
        SnapshotRetentionService retention = new SnapshotRetentionService(repository);
        UUID projectId = UUID.randomUUID();
        Path directory = repository.ensureProjectDirectory(projectId);
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        Path orphan = stale(directory.resolve(".snapshot-orphan.tmp"), now.minus(Duration.ofHours(25)));
        Path inFlight = stale(directory.resolve(".snapshot-inflight.tmp"), now.minus(Duration.ofMinutes(5)));
        Path pointer = stale(directory.resolve(".active-old.tmp"), now.minus(Duration.ofDays(3)));
        Path published = stale(directory.resolve("snapshot-abc-def.v2"), now.minus(Duration.ofDays(3)));
        Path unrelated = stale(directory.resolve(".snapshot-lookalike.txt"), now.minus(Duration.ofDays(3)));

        int deleted = retention.deleteOrphanPreparedSnapshots(projectId, now, Duration.ofHours(24));

        assertEquals(1, deleted);
        assertFalse(Files.exists(orphan), "a prepared snapshot older than the max age is an orphan");
        assertTrue(Files.exists(inFlight), "a recent temporary may belong to a staging in progress");
        assertTrue(Files.exists(pointer), "an active-pointer temporary is never touched");
        assertTrue(Files.exists(published));
        assertTrue(Files.exists(unrelated));
        assertEquals(0, retention.deleteOrphanPreparedSnapshots(UUID.randomUUID(), now, Duration.ofHours(24)),
                "a project without directory has nothing to reclaim");
    }

    @Test
    void policyApplicationSweepsOrphanPreparedSnapshotsUnderTheSameLease(@TempDir Path root) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(root);
        SnapshotRetentionService retention = new SnapshotRetentionService(repository);
        UUID projectId = UUID.randomUUID();
        Path directory = repository.ensureProjectDirectory(projectId);
        Files.writeString(directory.resolve("snapshot-active.v2"), "active");
        Path orphan = stale(directory.resolve(".snapshot-orphan.tmp"), Instant.now().minus(Duration.ofHours(30)));
        Path recent = Files.writeString(directory.resolve(".snapshot-recent.tmp"), "recent");

        retention.applyPolicy(projectId, "snapshot-active.v2", new SnapshotRetentionPolicy(2));

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(recent));
        assertEquals(Duration.ofHours(24), SnapshotRetentionService.DEFAULT_ORPHAN_MAX_AGE);
    }

    private static Path stale(Path file, Instant modifiedAt) throws Exception {
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt));
        return file;
    }
}
