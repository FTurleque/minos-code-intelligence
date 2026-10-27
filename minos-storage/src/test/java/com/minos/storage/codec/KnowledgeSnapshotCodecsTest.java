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
    void anUnknownVersionHasNoCodec() {
        IOException refusal = assertThrows(IOException.class, () -> KnowledgeSnapshotCodecs.forVersion(0));

        assertEquals("unsupported knowledge snapshot format version: 0", refusal.getMessage());
    }
}
