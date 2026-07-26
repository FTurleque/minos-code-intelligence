package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;

/** Version-specific binary snapshot codec, independent from file publication and active-pointer state. */
public interface SnapshotCodec {

    int formatVersion();

    String fileExtension();

    SnapshotEncoding write(Path file, CodeKnowledgeSnapshot snapshot) throws IOException;

    CodeKnowledgeSnapshot read(Path file) throws IOException;

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
