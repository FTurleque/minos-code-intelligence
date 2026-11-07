package com.minos.storage.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted ceiling bounds the read as well as the write: a snapshot file larger than the ceiling is refused
 * before any decoding, so raising the ceiling is a one-way change for the snapshots published under it.
 */
class SnapshotReadCeilingTest {

    private static final String SIZE_REFUSAL = "snapshot payload exceeds persisted byte limit";

    @Test
    void aFileAboveTheCeilingIsRefusedOnReadWithoutAPathInTheMessage(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("above-the-ceiling.knowledge");
        extendTo(file, SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES + 1L);

        for (SnapshotCodec codec : List.of(new SnapshotCodecV2(), new SnapshotCodecV3())) {
            IOException refused = assertThrows(IOException.class, () -> codec.read(file));
            String message = refused.getMessage();
            assertNotNull(message);
            assertTrue(message.startsWith(SIZE_REFUSAL), message);
            assertFalse(message.contains(directory.toString()), "the refusal must not carry a path: " + message);
        }
    }

    @Test
    void aFileAtTheCeilingPassesTheSizeCheckAndIsRefusedOnItsContent(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("at-the-ceiling.knowledge");
        extendTo(file, SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES);

        IOException refused = assertThrows(IOException.class, () -> new SnapshotCodecV3().read(file));

        assertFalse(String.valueOf(refused.getMessage()).startsWith(SIZE_REFUSAL),
                "a file of exactly the ceiling is within the size bound; only its zero content is refused: "
                        + refused.getMessage());
    }

    private static void extendTo(Path file, long bytes) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "rw")) {
            access.setLength(bytes);
        }
    }
}
