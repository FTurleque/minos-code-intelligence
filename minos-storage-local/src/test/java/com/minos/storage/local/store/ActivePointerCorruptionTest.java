package com.minos.storage.local.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H12 : un pointeur actif vide, tronqué ou de version inconnue fait échouer l'ouverture du snapshot actif
 * avec un message explicite, sans basculer silencieusement vers un autre snapshot.
 */
class ActivePointerCorruptionTest {

    @Test
    void anEmptyPointerIsRefused(@TempDir Path root) throws Exception {
        Path pointer = publishedPointer(root);
        Files.write(pointer, new byte[0]);

        assertEquals("truncated active snapshot pointer", refusal(root));
    }

    @Test
    void aPointerCutInTheMiddleIsRefused(@TempDir Path root) throws Exception {
        Path pointer = publishedPointer(root);
        byte[] bytes = Files.readAllBytes(pointer);
        Files.write(pointer, Arrays.copyOf(bytes, bytes.length / 2));

        assertEquals("truncated active snapshot pointer", refusal(root));
    }

    @Test
    void aPointerOfAnUnknownVersionIsRefused(@TempDir Path root) throws Exception {
        Path pointer = publishedPointer(root);
        byte[] bytes = Files.readAllBytes(pointer);
        ByteBuffer.wrap(bytes).putInt(4, 99);
        Files.write(pointer, bytes);

        String message = refusal(root);
        assertTrue(message.contains("version"), message);
    }

    @Test
    void aPointerWithTrailingBytesIsRefused(@TempDir Path root) throws Exception {
        Path pointer = publishedPointer(root);
        byte[] bytes = Files.readAllBytes(pointer);
        Files.write(pointer, Arrays.copyOf(bytes, bytes.length + 1));

        assertEquals("unexpected trailing data in active snapshot pointer", refusal(root));
    }

    private static final UUID PROJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Path publishedPointer(Path root) throws IOException {
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(PROJECT, "first", SnapshotPublishChild.symbols(PROJECT, "first", 3));
        store.publish(PROJECT, "second", SnapshotPublishChild.symbols(PROJECT, "second", 3));
        return root.resolve(PROJECT.toString()).resolve("active.pointer");
    }

    private static String refusal(Path root) {
        IOException failure = assertThrows(IOException.class,
                () -> new FileSymbolSnapshotStore(root).loadActiveKnowledge(PROJECT));
        return failure.getMessage();
    }
}
