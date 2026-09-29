package com.minos.storage.local.store;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot above the persisted ceiling is refused before any byte is written (A6, step 1): no
 * temporary file, no partial file, no pointer change, and a message that is safe to surface.
 */
class PersistedSnapshotLimitTest {

    static final String REFUSAL_PREFIX = "knowledge snapshot is too large to persist: ";
    static final String REFUSAL_LIMIT = " encoded bytes exceed the 268435456-byte limit (256 MiB); nothing was written";

    @Test
    void oversizedSnapshotLeavesNoFileAndKeepsTheActiveSnapshot(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "small", FileSymbolSnapshotStoreTest.symbols(projectId), List.of(), List.of());
        List<Path> before = listFiles(root);
        CodeKnowledgeSnapshot oversized = PersistedSizeFixtures.oversized(projectId, "oversized");

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "oversized",
                oversized.symbols(), oversized.occurrences(), oversized.relationships()));

        assertRefusalMessage(refused.getMessage());
        assertEquals(before, listFiles(root), "no temporary, partial or pointer file may change");
        assertEquals("small", store.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

    @Test
    void oversizedSnapshotIsRefusedBeforeTheProjectDirectoryIsTouched(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        // Any file-system write for this project would first need its directory; a regular file in its
        // place makes every such attempt fail differently. Only a refusal decided before I/O passes.
        Path blocker = Files.writeString(store.storageRoot().resolve(projectId.toString()), "not a directory");
        CodeKnowledgeSnapshot oversized = PersistedSizeFixtures.oversized(projectId, "oversized");

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "oversized",
                oversized.symbols(), oversized.occurrences(), oversized.relationships()));

        assertRefusalMessage(refused.getMessage());
        assertEquals("not a directory", Files.readString(blocker));
    }

    @Test
    void snapshotAtTheCeilingIsPersistedAndOneMoreByteIsRefused(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        SnapshotCodec codec = new SnapshotCodecV2();
        long ceiling = SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES;
        CodeKnowledgeSnapshot overByOne = PersistedSizeFixtures.ofEncodedSize(projectId, "over-by-one", ceiling + 1L, codec);
        CodeKnowledgeSnapshot atCeiling = PersistedSizeFixtures.ofEncodedSize(projectId, "at-ceiling", ceiling, codec);
        assertEquals(ceiling + 1L, codec.encodedSize(overByOne));
        assertEquals(ceiling, codec.encodedSize(atCeiling));

        IOException refused = assertThrows(IOException.class, () -> store.publish(projectId, "over-by-one",
                overByOne.symbols(), overByOne.occurrences(), overByOne.relationships()));
        assertRefusalMessage(refused.getMessage());
        assertTrue(refused.getMessage().contains(": " + (ceiling + 1L) + " encoded bytes"), refused.getMessage());

        store.publish(projectId, "at-ceiling", atCeiling.symbols(), atCeiling.occurrences(), atCeiling.relationships());
        List<Path> published = listFiles(store.storageRoot().resolve(projectId.toString())).stream()
                .filter(path -> path.toString().endsWith(".knowledge")).toList();
        assertEquals(1, published.size());
        assertEquals(ceiling, Files.size(store.storageRoot().resolve(projectId.toString()).resolve(published.getFirst())));
    }

    static void assertRefusalMessage(String message) {
        assertTrue(message != null && message.startsWith(REFUSAL_PREFIX) && message.endsWith(REFUSAL_LIMIT),
                "unexpected refusal message: " + message);
        assertEquals(message, PublicErrorMessages.sanitize(message, "fallback"),
                "the refusal must survive public-message sanitization unchanged");
    }

    private static List<Path> listFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(root::relativize).sorted().toList();
        }
    }
}
