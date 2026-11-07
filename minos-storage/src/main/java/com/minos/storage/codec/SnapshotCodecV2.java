package com.minos.storage.codec;

import com.minos.store.CodeKnowledgeSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** M3+ codec containing symbols, occurrences and relationships. */
public final class SnapshotCodecV2 implements SnapshotCodec {

    private final long maxPersistedBytes;

    /** A codec bound to the production ceiling. */
    public SnapshotCodecV2() {
        this(SnapshotLimits.production());
    }

    /** A codec that writes and reads up to {@code limits.maxPersistedBytes()}. */
    public SnapshotCodecV2(SnapshotLimits limits) {
        this.maxPersistedBytes = java.util.Objects.requireNonNull(limits, "limits").maxPersistedBytes();
    }

    @Override
    public long maxPersistedBytes() {
        return maxPersistedBytes;
    }

    @Override
    public int formatVersion() {
        return SnapshotBinaryCodecSupport.FORMAT_VERSION_V2;
    }

    @Override
    public String fileExtension() {
        return ".knowledge";
    }

    @Override
    public SnapshotEncoding write(Path file, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(snapshot, "snapshot");
        String checksum = SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2(file, snapshot, maxPersistedBytes);
        return new SnapshotEncoding(
                checksum,
                snapshot.symbols().size(),
                snapshot.occurrences().size(),
                snapshot.relationships().size()
        );
    }

    @Override
    public CodeKnowledgeSnapshot read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return SnapshotBinaryCodecSupport.readKnowledgeSnapshotV2(file, maxPersistedBytes);
    }

    @Override
    public CodeKnowledgeSnapshot readVerified(Path file, String expectedSha256) throws IOException {
        Objects.requireNonNull(file, "file");
        return SnapshotBinaryCodecSupport.readVerifiedKnowledgeSnapshotV2(file, expectedSha256, maxPersistedBytes);
    }

    @Override
    public long encodedSize(CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        return SnapshotBinaryCodecSupport.encodedKnowledgeSnapshotV2Size(snapshot);
    }

    public byte[] encodeToBytes(CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        return SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2ToBytes(snapshot, maxPersistedBytes);
    }

    public CodeKnowledgeSnapshot decodeFromBytes(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        return SnapshotBinaryCodecSupport.readKnowledgeSnapshotV2FromBytes(payload, maxPersistedBytes);
    }
}
