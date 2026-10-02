package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Knowledge snapshot codec with UTF-8 strings (ADR 0046): the V2 layout, strings framed by their length in
 * bytes. A snapshot holding an unpaired surrogate has no exact UTF-8 form: {@link #encodedSizeIfEncodable}
 * reports it and {@link KnowledgeSnapshotCodecs#select} writes such a snapshot in V2 instead.
 */
public final class SnapshotCodecV3 implements SnapshotCodec {

    @Override
    public int formatVersion() {
        return SnapshotBinaryCodecSupport.FORMAT_VERSION_V3;
    }

    @Override
    public String fileExtension() {
        return ".knowledge";
    }

    @Override
    public SnapshotEncoding write(Path file, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(snapshot, "snapshot");
        String checksum = SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV3(file, snapshot);
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
        return SnapshotBinaryCodecSupport.readKnowledgeSnapshotV3(file);
    }

    @Override
    public long encodedSize(CodeKnowledgeSnapshot snapshot) throws IOException {
        return encodedSizeIfEncodable(snapshot).orElseThrow(() -> new IOException(
                "knowledge snapshot holds an unpaired surrogate and has no exact UTF-8 form"));
    }

    /** Exact encoded size, or empty when a string holds an unpaired surrogate. */
    public OptionalLong encodedSizeIfEncodable(CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        long size = SnapshotBinaryCodecSupport.encodedKnowledgeSnapshotV3Size(snapshot);
        return size < 0L ? OptionalLong.empty() : OptionalLong.of(size);
    }
}
