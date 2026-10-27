package com.minos.storage.codec;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The V2 format is frozen: a payload written by the published base decodes to its known content and encodes back to
 * the same bytes, whichever backend stored it.
 */
class SnapshotReencodingTest {

    private static final String FIXTURES = "/snapshot-formats/base-d9ae1005/";

    @TempDir
    Path directory;

    @Test
    void aWellFormedV2PayloadIsReencodedByteForByte() throws IOException {
        assertReencodedIdentically("v2-well-formed.knowledge", LegacySnapshotContent.v2());
    }

    @Test
    void aV2PayloadWithALoneSurrogateIsReencodedByteForByte() throws IOException {
        assertReencodedIdentically("v2-lone-surrogate.knowledge", LegacySnapshotContent.v2WithLoneSurrogate());
    }

    private void assertReencodedIdentically(String fixture, CodeKnowledgeSnapshot expected) throws IOException {
        Path original = directory.resolve(fixture);
        try (InputStream input = SnapshotReencodingTest.class.getResourceAsStream(FIXTURES + fixture)) {
            if (input == null) {
                throw new IOException("missing fixture " + fixture);
            }
            Files.copy(input, original);
        }
        assertEquals(2, KnowledgeSnapshotCodecs.formatVersionOf(original));
        CodeKnowledgeSnapshot decoded = KnowledgeSnapshotCodecs.read(original);
        assertEquals(expected, decoded);

        Path rewritten = directory.resolve("rewritten-" + fixture);
        SnapshotCodec.SnapshotEncoding encoding = new SnapshotCodecV2().write(rewritten, decoded);

        assertEquals(new SnapshotIntegrityService().checksum(original), encoding.sha256());
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(rewritten));
    }
}
