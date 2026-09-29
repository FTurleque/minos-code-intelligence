package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** La taille qu'un codec annonce avant d'écrire est exactement celle qu'il écrit, pour chaque format (A6). */
class SnapshotEncodedSizeTest {

    @Test
    void v2AnnouncesExactlyTheBytesItWrites(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        assertExact(new SnapshotCodecV2(), CodecFixtures.everyBranch(projectId, "every-branch"), root.resolve("a.knowledge"));
        assertExact(new SnapshotCodecV2(), PersistedSizeFixtures.withTail(projectId, "large", 2, 12_345),
                root.resolve("b.knowledge"));
        assertExact(new SnapshotCodecV2(), new CodeKnowledgeSnapshot(projectId, "empty", List.of(), List.of(), List.of()),
                root.resolve("c.knowledge"));
    }

    @Test
    void v3AnnouncesExactlyTheBytesItWrites(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        assertExact(new SnapshotCodecV3(), LegacySnapshotContent.v2(), root.resolve("a.knowledge"));
        assertExact(new SnapshotCodecV3(), PersistedSizeFixtures.withTail(projectId, "large", 2, 12_345),
                root.resolve("b.knowledge"));
        assertExact(new SnapshotCodecV3(), new CodeKnowledgeSnapshot(projectId, "empty", List.of(), List.of(), List.of()),
                root.resolve("c.knowledge"));
    }

    @Test
    void v2BytesEncoderAgreesWithTheAnnouncedSize() throws Exception {
        CodeKnowledgeSnapshot snapshot = CodecFixtures.everyBranch(UUID.randomUUID(), "every-branch");
        SnapshotCodecV2 codec = new SnapshotCodecV2();

        assertEquals(codec.encodeToBytes(snapshot).length, codec.encodedSize(snapshot));
    }

    @Test
    void v1AnnouncesExactlyTheBytesItWrites(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot symbolsOnly = new CodeKnowledgeSnapshot(projectId, "legacy",
                FileSymbolSnapshotStoreTest.symbols(projectId), List.of(), List.of());

        assertExact(new SnapshotCodecV1(), symbolsOnly, root.resolve("a.symbols"));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCodecV1().encodedSize(CodecFixtures.everyBranch(projectId, "knowledge")));
    }

    @Test
    void anOverlongStringIsRefusedByTheSizeComputationAsByTheWriter() {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot overlong = new CodeKnowledgeSnapshot(projectId, "overlong",
                List.of(PersistedSizeFixtures.symbol(projectId, 0, "x".repeat(8 * 1024 * 1024 + 1))), List.of(), List.of());

        assertEquals("string exceeds snapshot limit",
                assertThrows(java.io.IOException.class, () -> new SnapshotCodecV2().encodedSize(overlong)).getMessage());
    }

    static void assertExact(SnapshotCodec codec, CodeKnowledgeSnapshot snapshot, Path file) throws Exception {
        long announced = codec.encodedSize(snapshot);
        codec.write(file, snapshot);
        assertEquals(Files.size(file), announced, "announced size of " + codec.getClass().getSimpleName());
    }
}
