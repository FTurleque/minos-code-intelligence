package com.minos.storage.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The format of a stored payload is read from its own header; a version no codec knows is refused, never guessed. */
class KnowledgeSnapshotCodecsTest {

    private static final int SNAPSHOT_MAGIC = 0x4D4E5359;

    @TempDir
    Path directory;

    @Test
    void aPayloadDeclaringAnUnknownVersionIsRefused() throws IOException {
        Path payload = directory.resolve("snapshot.knowledge");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(payload))) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(9);
            output.writeInt(0);
        }

        assertEquals(9, KnowledgeSnapshotCodecs.formatVersionOf(payload));
        IOException refusal = assertThrows(IOException.class, () -> KnowledgeSnapshotCodecs.read(payload));
        assertEquals("unsupported knowledge snapshot format version: 9", refusal.getMessage());
    }

    @Test
    void theHeaderOfAStoredPayloadGivesItsVersionBeforeThePayloadIsRead() throws IOException {
        Path payload = directory.resolve("v3.knowledge");
        new SnapshotCodecV3().write(payload, PersistedSizeFixtures.withTail(java.util.UUID.randomUUID(), "header", 0, 10));
        byte[] header = java.util.Arrays.copyOf(Files.readAllBytes(payload), 8);

        assertEquals(3, KnowledgeSnapshotCodecs.formatVersionOfHeader(header));
        assertEquals(-1, KnowledgeSnapshotCodecs.formatVersionOfHeader(java.util.Arrays.copyOf(header, 7)), "too short");
        assertEquals(-1, KnowledgeSnapshotCodecs.formatVersionOfHeader(null));
        header[0] = 0;
        assertEquals(-1, KnowledgeSnapshotCodecs.formatVersionOfHeader(header), "another magic");
    }

    @Test
    void anUnknownVersionHasNoCodec() {
        IOException refusal = assertThrows(IOException.class, () -> KnowledgeSnapshotCodecs.forVersion(0));

        assertEquals("unsupported knowledge snapshot format version: 0", refusal.getMessage());
    }
}
