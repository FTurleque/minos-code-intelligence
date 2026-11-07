package com.minos.storage.codec;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SymbolSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Historical M2 symbol-only codec. */
public final class SnapshotCodecV1 implements SnapshotCodec {

    private final long maxPersistedBytes;

    /** A codec bound to the production ceiling. */
    public SnapshotCodecV1() {
        this(SnapshotLimits.production());
    }

    /** A codec that writes and reads up to {@code limits.maxPersistedBytes()}. */
    public SnapshotCodecV1(SnapshotLimits limits) {
        this.maxPersistedBytes = java.util.Objects.requireNonNull(limits, "limits").maxPersistedBytes();
    }

    @Override
    public long maxPersistedBytes() {
        return maxPersistedBytes;
    }

    @Override
    public int formatVersion() {
        return SnapshotBinaryCodecSupport.FORMAT_VERSION_V1;
    }

    @Override
    public String fileExtension() {
        return ".symbols";
    }

    @Override
    public SnapshotEncoding write(Path file, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(snapshot, "snapshot");
        SymbolSnapshot legacy = legacy(snapshot);
        String checksum = SnapshotBinaryCodecSupport.writeSymbolSnapshotV1(file, legacy, maxPersistedBytes);
        return new SnapshotEncoding(checksum, legacy.symbols().size(), 0, 0);
    }

    @Override
    public long encodedSize(CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        return SnapshotBinaryCodecSupport.encodedSymbolSnapshotV1Size(legacy(snapshot));
    }

    private static SymbolSnapshot legacy(CodeKnowledgeSnapshot snapshot) {
        if (!snapshot.occurrences().isEmpty() || !snapshot.relationships().isEmpty()) {
            throw new IllegalArgumentException("snapshot codec v1 supports symbols only");
        }
        return new SymbolSnapshot(snapshot.projectId(), snapshot.snapshotId(), snapshot.symbols());
    }

    @Override
    public CodeKnowledgeSnapshot read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return SnapshotBinaryCodecSupport.fromLegacy(
                SnapshotBinaryCodecSupport.readSymbolSnapshotV1(file, maxPersistedBytes)
        );
    }
}
