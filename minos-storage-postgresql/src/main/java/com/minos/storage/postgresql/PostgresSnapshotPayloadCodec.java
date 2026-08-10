package com.minos.storage.postgresql;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotCodec;
import com.minos.store.SnapshotCodecV2;

import java.io.IOException;
import java.nio.file.Path;

final class PostgresSnapshotPayloadCodec {
    private final SnapshotCodecV2 codec = new SnapshotCodecV2();

    SnapshotCodec.SnapshotEncoding encode(Path target, CodeKnowledgeSnapshot snapshot) throws IOException {
        return codec.write(target, snapshot);
    }

    CodeKnowledgeSnapshot decode(Path payload) throws IOException {
        return codec.read(payload);
    }
}
