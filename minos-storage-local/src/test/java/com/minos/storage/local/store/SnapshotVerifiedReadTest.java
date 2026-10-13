package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A snapshot is decoded and authenticated in one pass over its bytes, and nothing decoded from content
 * that fails the SHA-256 check is returned: altered, truncated or extended content is a checksum mismatch.
 */
class SnapshotVerifiedReadTest {

    private static final String MISMATCH = "active symbol snapshot checksum mismatch";

    private CodeKnowledgeSnapshot written;
    private byte[] payload;
    private String sha256;

    @BeforeEach
    void writeSnapshot(@TempDir Path directory) throws Exception {
        written = QueryViewWeightFixtures.repository(UUID.randomUUID(), "verified", 3,
                List.of(QueryViewWeightFixtures.ASCII, QueryViewWeightFixtures.CJK));
        Path file = directory.resolve("verified.knowledge");
        sha256 = new SnapshotCodecV3().write(file, written).sha256();
        payload = Files.readAllBytes(file);
    }

    @Test
    void authenticContentIsDecodedInASinglePassOverItsBytes() throws Exception {
        CountingInputStream source = new CountingInputStream(payload);

        CodeKnowledgeSnapshot decoded = SnapshotBinaryCodecSupport.readVerifiedKnowledgeSnapshotV3(source, sha256);

        assertEquals(written, decoded);
        assertEquals(payload.length, source.bytesRead, "every byte is read exactly once");
    }

    @Test
    void theFileEntryPointReadsWhatTheStreamEntryPointReads(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("copy.knowledge");
        Files.write(file, payload);

        assertEquals(written, new SnapshotCodecV3().readVerified(file, sha256));
    }

    @Test
    void alteredContentIsAChecksumMismatch() {
        byte[] altered = payload.clone();
        altered[altered.length / 2] ^= 0x01;

        assertMismatch(altered);
    }

    @Test
    void truncatedContentIsAChecksumMismatchNotADecodingError() {
        assertMismatch(Arrays.copyOf(payload, payload.length - 7));
        assertMismatch(Arrays.copyOf(payload, 12));
        assertMismatch(new byte[0]);
    }

    @Test
    void extendedContentIsAChecksumMismatch() {
        byte[] extended = Arrays.copyOf(payload, payload.length + 3);

        assertMismatch(extended);
    }

    @Test
    void authenticContentWithAnotherExpectedChecksumIsRefused() {
        IOException refused = assertThrows(IOException.class, () -> SnapshotBinaryCodecSupport
                .readVerifiedKnowledgeSnapshotV3(new ByteArrayInputStream(payload), "0".repeat(64)));

        assertEquals(MISMATCH, refused.getMessage());
    }

    @Test
    void theUtf16FormatIsVerifiedInASinglePassToo(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("verified.v2");
        SnapshotCodecV2 codec = new SnapshotCodecV2();
        String legacySha = codec.write(file, written).sha256();
        byte[] legacy = Files.readAllBytes(file);
        CountingInputStream source = new CountingInputStream(legacy);

        assertEquals(written, SnapshotBinaryCodecSupport.readVerifiedKnowledgeSnapshotV2(source, legacySha));
        assertEquals(legacy.length, source.bytesRead);
        assertEquals(written, codec.readVerified(file, legacySha));
        byte[] altered = legacy.clone();
        altered[altered.length - 1] ^= 0x01;
        IOException refused = assertThrows(IOException.class, () -> SnapshotBinaryCodecSupport
                .readVerifiedKnowledgeSnapshotV2(new ByteArrayInputStream(altered), legacySha));
        assertEquals(MISMATCH, refused.getMessage());
    }

    private void assertMismatch(byte[] content) {
        IOException refused = assertThrows(IOException.class, () -> SnapshotBinaryCodecSupport
                .readVerifiedKnowledgeSnapshotV3(new ByteArrayInputStream(content), sha256));

        assertEquals(MISMATCH, refused.getMessage());
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        CountingInputStream(byte[] content) {
            super(new ByteArrayInputStream(content));
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }
    }
}
