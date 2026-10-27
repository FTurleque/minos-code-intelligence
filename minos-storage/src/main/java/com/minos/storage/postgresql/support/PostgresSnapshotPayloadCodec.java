package com.minos.storage.postgresql.support;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.storage.codec.KnowledgeSnapshotCodecs;
import com.minos.storage.codec.SnapshotCodec;

import java.io.IOException;
import java.nio.file.Path;

/**
 * PostgreSQL payloads are the file-store encodings, self-described by their header (magic and format
 * version): new payloads follow {@link KnowledgeSnapshotCodecs#select}, stored V2 payloads stay readable.
 */
public final class PostgresSnapshotPayloadCodec {

    public KnowledgeSnapshotCodecs.Selection select(CodeKnowledgeSnapshot snapshot) throws IOException {
        return KnowledgeSnapshotCodecs.select(snapshot);
    }

    public SnapshotCodec.SnapshotEncoding encode(KnowledgeSnapshotCodecs.Selection selection, Path target,
                                          CodeKnowledgeSnapshot snapshot) throws IOException {
        return selection.codec().write(target, snapshot);
    }

    public int formatVersion(Path payload) throws IOException {
        return KnowledgeSnapshotCodecs.formatVersionOf(payload);
    }

    public CodeKnowledgeSnapshot decode(Path payload) throws IOException {
        return KnowledgeSnapshotCodecs.read(payload);
    }
}
