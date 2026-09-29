package com.minos.storage.postgresql;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.storage.local.store.KnowledgeSnapshotCodecs;
import com.minos.storage.local.store.SnapshotCodec;

import java.io.IOException;
import java.nio.file.Path;

/**
 * PostgreSQL payloads are the file-store encodings, self-described by their header (magic and format
 * version): new payloads follow {@link KnowledgeSnapshotCodecs#select}, stored V2 payloads stay readable.
 */
final class PostgresSnapshotPayloadCodec {

    KnowledgeSnapshotCodecs.Selection select(CodeKnowledgeSnapshot snapshot) throws IOException {
        return KnowledgeSnapshotCodecs.select(snapshot);
    }

    SnapshotCodec.SnapshotEncoding encode(KnowledgeSnapshotCodecs.Selection selection, Path target,
                                          CodeKnowledgeSnapshot snapshot) throws IOException {
        return selection.codec().write(target, snapshot);
    }

    int formatVersion(Path payload) throws IOException {
        return KnowledgeSnapshotCodecs.formatVersionOf(payload);
    }

    CodeKnowledgeSnapshot decode(Path payload) throws IOException {
        return KnowledgeSnapshotCodecs.read(payload);
    }
}
