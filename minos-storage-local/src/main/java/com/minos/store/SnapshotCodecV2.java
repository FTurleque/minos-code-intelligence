package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** M3+ codec containing symbols, occurrences and relationships. */
public final class SnapshotCodecV2 implements SnapshotCodec {

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
        String checksum = SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2(file, snapshot);
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
        return SnapshotBinaryCodecSupport.readKnowledgeSnapshotV2(file);
    }

    public byte[] encodeToBytes(CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        return SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2ToBytes(snapshot);
    }

    public CodeKnowledgeSnapshot decodeFromBytes(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        return SnapshotBinaryCodecSupport.readKnowledgeSnapshotV2FromBytes(payload);
    }
}
