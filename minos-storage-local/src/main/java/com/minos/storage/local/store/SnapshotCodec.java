package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import java.io.IOException;
import java.nio.file.Path;

/** Version-specific binary snapshot codec, independent from file publication and active-pointer state. */
public interface SnapshotCodec {

    /** Ceiling of one persisted snapshot, in encoded bytes, shared by every store that uses these codecs. */
    long MAX_PERSISTED_SNAPSHOT_BYTES = SnapshotBinaryCodecSupport.MAX_PERSISTED_SNAPSHOT_BYTES;

    int formatVersion();

    String fileExtension();

    SnapshotEncoding write(Path file, CodeKnowledgeSnapshot snapshot) throws IOException;

    CodeKnowledgeSnapshot read(Path file) throws IOException;

    /**
     * Exact number of bytes {@link #write} produces for {@code snapshot}, computed without writing: the
     * writer's own traversal runs against a byte counter.
     */
    long encodedSize(CodeKnowledgeSnapshot snapshot) throws IOException;

    /**
     * Returns the encoded size, or refuses the snapshot when it exceeds
     * {@link #MAX_PERSISTED_SNAPSHOT_BYTES}. Stores call it before creating any file, so a refused
     * snapshot leaves nothing behind; the message carries sizes only and is safe to surface.
     *
     * <p>No earlier criterion is sound: the encoded-to-SCIP ratio ranges from 9 to 14 across real
     * corpora, and SCIP pre-analysis counts bound the persisted entities from above, not from below
     * (ingestion may skip some), so any refusal before ingestion could reject a snapshot that fits.</p>
     */
    default long requirePersistable(CodeKnowledgeSnapshot snapshot) throws IOException {
        return SnapshotBinaryCodecSupport.requirePersistable(encodedSize(snapshot));
    }

    record SnapshotEncoding(
            String sha256,
            int symbolCount,
            int occurrenceCount,
            int relationshipCount
    ) {
        public SnapshotEncoding {
            if (sha256 == null || sha256.isBlank()) {
                throw new IllegalArgumentException("sha256 must not be blank");
            }
            if (symbolCount < 0 || occurrenceCount < 0 || relationshipCount < 0) {
                throw new IllegalArgumentException("snapshot counts must not be negative");
            }
        }
    }
}
