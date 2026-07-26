package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotPersistenceComponentsTest {

    @Test
    void activePointerRoundTripsV1AndV2Metadata(@TempDir Path root) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(root);
        ActiveSnapshotRepository active = new ActiveSnapshotRepository(repository);
        UUID projectId = UUID.randomUUID();

        SnapshotDescriptor v1 = new SnapshotDescriptor(
                1, "legacy", "snapshot-legacy.symbols", "a".repeat(64), 4, 0, 0);
        active.promote(projectId, v1);
        assertEquals(v1, active.read(projectId).orElseThrow());

        SnapshotDescriptor v2 = new SnapshotDescriptor(
                2, "knowledge", "snapshot-knowledge.knowledge", "b".repeat(64), 4, 2, 3);
        active.promote(projectId, v2);
        assertEquals(v2, active.read(projectId).orElseThrow());

        Files.write(
                repository.projectDirectory(projectId).resolve("active.pointer"),
                ByteBuffer.allocate(8).putInt(0x4D4E4150).putInt(99).array()
        );
        java.io.IOException unsupported = assertThrows(
                java.io.IOException.class,
                () -> active.read(projectId)
        );
        assertEquals("unsupported active snapshot pointer version: 99", unsupported.getMessage());
    }

    @Test
    void retentionDeletesOnlyExplicitHistoricalFiles(@TempDir Path root) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(root);
        SnapshotRetentionService retention = new SnapshotRetentionService(repository);
        UUID projectId = UUID.randomUUID();
        Path directory = repository.ensureProjectDirectory(projectId);
        Path active = Files.writeString(directory.resolve("snapshot-active.knowledge"), "active");
        Path old = Files.writeString(directory.resolve("snapshot-old.knowledge"), "old");

        assertEquals(
                List.of("snapshot-active.knowledge", "snapshot-old.knowledge"),
                retention.listSnapshotFiles(projectId)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> retention.deleteHistoricalSnapshots(
                        projectId,
                        List.of(active.getFileName().toString()),
                        active.getFileName().toString()
                )
        );

        assertEquals(1, retention.deleteHistoricalSnapshots(
                projectId,
                List.of(old.getFileName().toString()),
                active.getFileName().toString()
        ));
        assertTrue(Files.isRegularFile(active));
        assertFalse(Files.exists(old));
    }

    @Test
    void boundedRetentionKeepsActiveAndNewestHistoricalFiles(@TempDir Path root) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(root);
        ActiveSnapshotRepository activeRepository = new ActiveSnapshotRepository(repository);
        UUID projectId = UUID.randomUUID();
        Path directory = repository.ensureProjectDirectory(projectId);
        Path oldest = Files.writeString(directory.resolve("snapshot-01.knowledge"), "oldest");
        Path middle = Files.writeString(directory.resolve("snapshot-02.knowledge"), "middle");
        Path newest = Files.writeString(directory.resolve("snapshot-03.knowledge"), "newest");
        Path active = Files.writeString(directory.resolve("snapshot-active.knowledge"), "active");
        Files.setLastModifiedTime(oldest, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(middle, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        Files.setLastModifiedTime(newest, FileTime.from(Instant.parse("2026-01-03T00:00:00Z")));
        Files.setLastModifiedTime(active, FileTime.from(Instant.parse("2026-01-04T00:00:00Z")));
        activeRepository.promote(projectId, new SnapshotDescriptor(
                2, "active", active.getFileName().toString(), "a".repeat(64), 1, 1, 1));

        SnapshotRetentionService.RetentionResult result = new SnapshotCompactionService(root)
                .compact(projectId, new SnapshotRetentionPolicy(2));

        assertEquals("snapshot-active.knowledge", result.activeFileName());
        assertEquals(List.of("snapshot-03.knowledge", "snapshot-02.knowledge"),
                result.retainedHistoricalFiles());
        assertEquals(List.of("snapshot-01.knowledge"), result.deletedHistoricalFiles());
        assertTrue(Files.isRegularFile(active));
        assertTrue(Files.isRegularFile(newest));
        assertTrue(Files.isRegularFile(middle));
        assertFalse(Files.exists(oldest));
    }

    @Test
    void integrityDetectsChecksumAndPointerMetadataMismatch(@TempDir Path root) throws Exception {
        SnapshotIntegrityService integrity = new SnapshotIntegrityService();
        Path file = Files.writeString(root.resolve("snapshot.bin"), "payload");
        String checksum = integrity.checksum(file);
        integrity.verifyChecksum(file, checksum);

        assertThrows(java.io.IOException.class, () -> integrity.verifyChecksum(file, "0".repeat(64)));

        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of()
        );
        SnapshotDescriptor descriptor = new SnapshotDescriptor(
                2,
                "other",
                "snapshot.knowledge",
                checksum,
                snapshot.symbols().size(),
                0,
                0
        );
        assertThrows(
                java.io.IOException.class,
                () -> integrity.verifyMetadata(snapshot, projectId, descriptor)
        );
    }
}
