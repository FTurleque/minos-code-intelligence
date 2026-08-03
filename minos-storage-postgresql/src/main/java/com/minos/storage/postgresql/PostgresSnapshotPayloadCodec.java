package com.minos.storage.postgresql;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotCodecV2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PostgresSnapshotPayloadCodec {
    private final SnapshotCodecV2 codec = new SnapshotCodecV2();

    byte[] encode(CodeKnowledgeSnapshot snapshot) throws IOException {
        Path temporary = Files.createTempFile("minos-pg-snapshot-", ".knowledge");
        try {
            codec.write(temporary, snapshot);
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    CodeKnowledgeSnapshot decode(byte[] payload) throws IOException {
        Path temporary = Files.createTempFile("minos-pg-snapshot-read-", ".knowledge");
        try {
            Files.write(temporary, payload);
            return codec.read(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
