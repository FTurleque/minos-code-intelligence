package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SymbolSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Historical M2 symbol-only codec. */
public final class SnapshotCodecV1 implements SnapshotCodec {

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
        String checksum = SnapshotBinaryCodecSupport.writeSymbolSnapshotV1(file, legacy);
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
                SnapshotBinaryCodecSupport.readSymbolSnapshotV1(file)
        );
    }
}
