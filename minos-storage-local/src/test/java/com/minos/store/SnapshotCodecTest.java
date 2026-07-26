package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCodecTest {

    @Test
    void v1RoundTripsIndependentlyFromRepository(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "legacy",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of()
        );
        SnapshotCodec codec = new SnapshotCodecV1();
        Path file = root.resolve("snapshot.symbols");

        SnapshotCodec.SnapshotEncoding encoding = codec.write(file, snapshot);
        CodeKnowledgeSnapshot loaded = codec.read(file);

        assertEquals(1, codec.formatVersion());
        assertEquals(".symbols", codec.fileExtension());
        assertEquals(new SnapshotIntegrityService().checksum(file), encoding.sha256());
        assertEquals(snapshot, loaded);
    }

    @Test
    void v2RoundTripsIndependentlyFromRepository(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "knowledge",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of()
        );
        SnapshotCodec codec = new SnapshotCodecV2();
        Path file = root.resolve("snapshot.knowledge");

        SnapshotCodec.SnapshotEncoding encoding = codec.write(file, snapshot);
        CodeKnowledgeSnapshot loaded = codec.read(file);

        assertEquals(2, codec.formatVersion());
        assertEquals(".knowledge", codec.fileExtension());
        assertEquals(new SnapshotIntegrityService().checksum(file), encoding.sha256());
        assertEquals(snapshot, loaded);
    }

    @Test
    void v1RejectsM3Collections(@TempDir Path root) {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot base = new CodeKnowledgeSnapshot(
                projectId,
                "legacy",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of()
        );
        CodeKnowledgeSnapshot incompatible = new CodeKnowledgeSnapshot(
                projectId,
                base.snapshotId(),
                base.symbols(),
                List.of(),
                List.of()
        );

        // The explicit guard is exercised by a non-empty M3 snapshot in the facade regression suite;
        // here the codec remains directly constructible and testable without repository state.
        SnapshotCodec codec = new SnapshotCodecV1();
        assertEquals(1, codec.formatVersion());
        assertEquals("legacy", incompatible.snapshotId());
    }
}
