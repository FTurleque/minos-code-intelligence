package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Stable bridge exposing MINOS' canonical v2 knowledge-snapshot binary format to
 * alternate persistence backends without exposing the internal codec hierarchy.
 */
public final class CodeKnowledgeSnapshotBinaryCodec {
    private final SnapshotCodec delegate = new SnapshotCodecV2();

    public void write(Path target, CodeKnowledgeSnapshot snapshot) throws IOException {
        delegate.write(Objects.requireNonNull(target, "target"), Objects.requireNonNull(snapshot, "snapshot"));
    }

    public CodeKnowledgeSnapshot read(Path source) throws IOException {
        return delegate.read(Objects.requireNonNull(source, "source"));
    }
}
