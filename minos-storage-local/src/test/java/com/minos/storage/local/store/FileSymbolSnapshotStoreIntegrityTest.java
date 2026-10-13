package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An active snapshot whose bytes are not the published ones is never served: no view is returned, cached
 * or counted as loaded, and the refusal is the checksum mismatch whatever the damage.
 */
class FileSymbolSnapshotStoreIntegrityTest {

    private static final String MISMATCH = "active symbol snapshot checksum mismatch";

    @Test
    void anAlteredSnapshotIsRefusedAndNeverCached(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = publish(root, projectId);
        Path snapshot = snapshotFile(root);
        byte[] content = Files.readAllBytes(snapshot);
        content[content.length / 2] ^= 0x01;
        Files.write(snapshot, content);

        assertRefused(store, projectId);
        assertRefused(store, projectId);
    }

    @Test
    void aTruncatedSnapshotIsAChecksumMismatch(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = publish(root, projectId);
        Path snapshot = snapshotFile(root);
        byte[] content = Files.readAllBytes(snapshot);
        Files.write(snapshot, Arrays.copyOf(content, content.length - 9));

        assertRefused(store, projectId);
    }

    @Test
    void aSnapshotWithTrailingBytesIsAChecksumMismatch(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = publish(root, projectId);
        Files.write(snapshotFile(root), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);

        assertRefused(store, projectId);
    }

    @Test
    void anIntactSnapshotIsServedAgainAfterARefusedOne(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = publish(root, projectId);
        Path snapshot = snapshotFile(root);
        byte[] intact = Files.readAllBytes(snapshot);
        byte[] altered = intact.clone();
        altered[altered.length - 1] ^= 0x01;
        Files.write(snapshot, altered);
        assertRefused(store, projectId);

        Files.write(snapshot, intact);

        assertEquals("integrity", store.loadActiveQueryView(projectId).orElseThrow().snapshot().snapshotId());
        assertEquals(1, store.cacheStats().fullSnapshotLoads());
        assertEquals(1, store.cacheStats().entries());
    }

    private static FileSymbolSnapshotStore publish(Path root, UUID projectId) throws IOException {
        CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, "integrity", 2,
                List.<String[]>of(QueryViewWeightFixtures.ASCII));
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "integrity", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        return store;
    }

    private static void assertRefused(FileSymbolSnapshotStore store, UUID projectId) {
        IOException refused = assertThrows(IOException.class, () -> store.loadActiveQueryView(projectId));

        assertEquals(MISMATCH, refused.getMessage());
        assertEquals(0, store.cacheStats().entries(), "nothing is cached");
        assertEquals(0, store.cacheStats().fullSnapshotLoads(), "nothing is counted as loaded");
        assertEquals(0, store.cacheStats().queryViewBuilds(), "no view is built");
    }

    private static Path snapshotFile(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".knowledge")).findFirst().orElseThrow();
        }
    }
}
