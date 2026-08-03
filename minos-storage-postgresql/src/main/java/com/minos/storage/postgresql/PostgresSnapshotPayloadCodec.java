package com.minos.storage.postgresql;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotCodecV2;

import java.io.IOException;

final class PostgresSnapshotPayloadCodec {
    private final SnapshotCodecV2 codec = new SnapshotCodecV2();

    byte[] encode(CodeKnowledgeSnapshot snapshot) throws IOException {
        return codec.encodeToBytes(snapshot);
    }

    CodeKnowledgeSnapshot decode(byte[] payload) throws IOException {
        return codec.decodeFromBytes(payload);
    }
}
