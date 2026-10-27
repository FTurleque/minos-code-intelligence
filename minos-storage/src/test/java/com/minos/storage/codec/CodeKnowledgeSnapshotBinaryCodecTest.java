package com.minos.storage.codec;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeKnowledgeSnapshotBinaryCodecTest {

    @Test
    void writesTheCanonicalV2FormatAndReadsItBack(@TempDir Path temp) throws Exception {
        CodeKnowledgeSnapshot snapshot = CodecFixtures.everyBranch(UUID.randomUUID(), "snap-1");
        CodeKnowledgeSnapshotBinaryCodec codec = new CodeKnowledgeSnapshotBinaryCodec();
        Path file = temp.resolve("snapshot.bin");

        codec.write(file, snapshot);

        assertArrayEquals(new SnapshotCodecV2().encodeToBytes(snapshot), Files.readAllBytes(file),
                "the bridge must persist exactly the v2 encoding, not a format of its own");
        assertEquals(snapshot, codec.read(file));
    }

    @Test
    void refusesNullArgumentsWithoutCreatingAFile(@TempDir Path temp) {
        CodeKnowledgeSnapshotBinaryCodec codec = new CodeKnowledgeSnapshotBinaryCodec();
        CodeKnowledgeSnapshot snapshot = CodecFixtures.everyBranch(UUID.randomUUID(), "snap-1");
        Path file = temp.resolve("snapshot.bin");

        NullPointerException target = assertThrows(NullPointerException.class, () -> codec.write(null, snapshot));
        assertEquals("target", target.getMessage());
        NullPointerException missing = assertThrows(NullPointerException.class, () -> codec.write(file, null));
        assertEquals("snapshot", missing.getMessage());
        NullPointerException source = assertThrows(NullPointerException.class, () -> codec.read(null));
        assertEquals("source", source.getMessage());
        assertFalse(Files.exists(file), "a refused write must leave nothing behind");
    }

    @Test
    void refusesAMissingOrTruncatedSource(@TempDir Path temp) throws Exception {
        CodeKnowledgeSnapshotBinaryCodec codec = new CodeKnowledgeSnapshotBinaryCodec();
        Path complete = temp.resolve("complete.bin");
        codec.write(complete, CodecFixtures.everyBranch(UUID.randomUUID(), "snap-1"));
        byte[] bytes = Files.readAllBytes(complete);
        Path truncated = Files.write(temp.resolve("truncated.bin"), Arrays.copyOf(bytes, bytes.length / 2));
        Path absent = temp.resolve("absent.bin");

        assertThrows(IOException.class, () -> codec.read(absent));
        assertThrows(IOException.class, () -> codec.read(truncated));
    }
}
