package com.minos.storage.codec;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted ceiling bounds the read as well as the write: a snapshot file larger than the ceiling is refused
 * before any decoding, so raising the ceiling is a one-way change for the snapshots published under it. The
 * ceiling is the one the codec was given, so the tests lower it instead of building a file of the production size.
 */
class SnapshotReadCeilingTest {

    private static final String SIZE_REFUSAL = "snapshot payload exceeds persisted byte limit";
    private static final long CEILING = 1024L * 1024L;
    private static final SnapshotLimits LIMITS = SnapshotLimits.production().withMaxPersistedBytes(CEILING);

    @Test
    void aFileAboveTheCeilingIsRefusedOnReadWithoutAPathInTheMessage(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("above-the-ceiling.knowledge");
        extendTo(file, CEILING + 1L);

        for (SnapshotCodec codec : List.of(new SnapshotCodecV2(LIMITS), new SnapshotCodecV3(LIMITS))) {
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
        extendTo(file, CEILING);

        IOException refused = assertThrows(IOException.class, () -> new SnapshotCodecV3(LIMITS).read(file));

        assertFalse(String.valueOf(refused.getMessage()).startsWith(SIZE_REFUSAL),
                "a file of exactly the ceiling is within the size bound; only its zero content is refused: "
                        + refused.getMessage());
    }

    @Test
    void aSnapshotPublishedUnderARaisedCeilingIsReadUnderIt(@TempDir Path directory) throws Exception {
        UUID projectId = UUID.randomUUID();
        // About 1.3 MB in V3: above the former ceiling of 1 MiB, below the raised one.
        CodeKnowledgeSnapshot snapshot = PersistedSizeFixtures.withTail(projectId, "raised", 0, 1_300_000);
        SnapshotLimits raised = SnapshotLimits.production().withMaxPersistedBytes(2L * CEILING);
        Path file = directory.resolve("raised.knowledge");
        String sha256 = new SnapshotCodecV3(raised).write(file, snapshot).sha256();

        assertEquals(snapshot, new SnapshotCodecV3(raised).read(file));
        assertEquals(snapshot, new SnapshotCodecV3(raised).readVerified(file, sha256));
        IOException refused = assertThrows(IOException.class, () -> new SnapshotCodecV3(LIMITS).read(file));
        assertTrue(refused.getMessage().startsWith(SIZE_REFUSAL), refused.getMessage());
        assertThrows(IOException.class, () -> KnowledgeSnapshotCodecs.read(file, LIMITS));
        assertEquals(snapshot, KnowledgeSnapshotCodecs.read(file, raised));
    }

    private static void extendTo(Path file, long bytes) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "rw")) {
            access.setLength(bytes);
        }
    }
}
