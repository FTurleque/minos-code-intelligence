package com.minos.storage.postgresql;

import com.minos.storage.local.store.SnapshotCodecV2;
import com.minos.storage.local.store.SnapshotIntegrityService;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotQueryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les payloads V2 écrits par le code de {@code d9ae1005} restent lisibles en PostgreSQL après l'introduction de
 * V3, le ré-import du même snapshot ne bute plus sur « different content », et un nouvel import est stocké en
 * V3, son format étant lu dans son propre en-tête (ADR 0046).
 */
class PostgresSnapshotFormatCompatibilityTest extends PostgresTestSupport {

    private static final String FIXTURES = "/snapshot-formats/base-d9ae1005/";
    private static final UUID V2_PROJECT = UUID.fromString("a6000000-0000-4000-8000-000000000002");
    private static final UUID LONE_SURROGATE_PROJECT = UUID.fromString("a6000000-0000-4000-8000-000000000003");

    @TempDir
    Path tempDir;

    @Test
    void legacyV2PayloadLoadsAndReimportingTheSameSnapshotKeepsIt() throws Exception {
        byte[] legacy = resource("v2-well-formed.knowledge");
        CodeKnowledgeSnapshot expected = insertLegacy(V2_PROJECT, "legacy-v2", legacy);
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);

        SnapshotQueryView view = store.loadActiveQueryView(V2_PROJECT).orElseThrow();
        assertEquals(expected, view.snapshot());
        assertEquals(2, view.descriptor().formatVersion());

        store.publish(V2_PROJECT, "legacy-v2", expected.symbols(), expected.occurrences(), expected.relationships());

        assertArrayEquals(legacy, storedPayload(V2_PROJECT, "legacy-v2"), "the legacy V2 payload is kept as is");
        assertEquals(expected, store.loadActiveKnowledge(V2_PROJECT).orElseThrow());
        IOException conflict = assertThrows(IOException.class, () -> store.publish(V2_PROJECT, "legacy-v2",
                expected.symbols().subList(0, 1), expected.occurrences(), expected.relationships()));
        assertTrue(conflict.getMessage().contains("different content"), conflict.getMessage());

        store.publish(V2_PROJECT, "after-v3", expected.symbols(), expected.occurrences(), expected.relationships());
        assertEquals(3, ByteBuffer.wrap(storedPayload(V2_PROJECT, "after-v3"), 4, 4).getInt());
        SnapshotQueryView promoted = store.loadActiveQueryView(V2_PROJECT).orElseThrow();
        assertEquals(3, promoted.descriptor().formatVersion());
        assertEquals(new CodeKnowledgeSnapshot(V2_PROJECT, "after-v3", expected.symbols(), expected.occurrences(),
                expected.relationships()), promoted.snapshot());
    }

    @Test
    void legacyPayloadWithAnUnpairedSurrogateLoadsExactlyAndIsRepublishedInV2() throws Exception {
        CodeKnowledgeSnapshot expected = insertLegacy(LONE_SURROGATE_PROJECT, "legacy-v2-lone-surrogate",
                resource("v2-lone-surrogate.knowledge"));
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        assertEquals(expected, store.loadActiveKnowledge(LONE_SURROGATE_PROJECT).orElseThrow());

        store.publish(LONE_SURROGATE_PROJECT, "orphan-again",
                expected.symbols(), expected.occurrences(), expected.relationships());

        assertEquals(2, ByteBuffer.wrap(storedPayload(LONE_SURROGATE_PROJECT, "orphan-again"), 4, 4).getInt());
        CodeKnowledgeSnapshot loaded = store.loadActiveKnowledge(LONE_SURROGATE_PROJECT).orElseThrow();
        assertEquals(expected.occurrences(), loaded.occurrences());
        assertTrue(loaded.occurrences().get(1).symbolRef().toString().contains("\uD800"));
    }

    /** Stocke un payload comme le faisait le code de base : octets bruts, sha256 et comptes, puis activation. */
    private CodeKnowledgeSnapshot insertLegacy(UUID projectId, String snapshotId, byte[] payload) throws Exception {
        Path file = Files.write(tempDir.resolve(snapshotId + ".knowledge"), payload);
        CodeKnowledgeSnapshot snapshot = new SnapshotCodecV2().read(file);
        String sha = new SnapshotIntegrityService().checksum(file);
        connections.withConnection(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO knowledge_snapshots(project_id,snapshot_id,payload,sha256,symbol_count,"
                            + "occurrence_count,relationship_count) VALUES (?,?,?,?,?,?,?)");
                 PreparedStatement activate = connection.prepareStatement(
                         "INSERT INTO knowledge_active(project_id,snapshot_id) VALUES (?,?)")) {
                insert.setObject(1, projectId);
                insert.setString(2, snapshotId);
                insert.setBytes(3, payload);
                insert.setString(4, sha);
                insert.setInt(5, snapshot.symbols().size());
                insert.setInt(6, snapshot.occurrences().size());
                insert.setInt(7, snapshot.relationships().size());
                insert.executeUpdate();
                activate.setObject(1, projectId);
                activate.setString(2, snapshotId);
                activate.executeUpdate();
                return null;
            }
        });
        return snapshot;
    }

    private byte[] storedPayload(UUID projectId, String snapshotId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT payload FROM knowledge_snapshots WHERE project_id=? AND snapshot_id=?")) {
                statement.setObject(1, projectId);
                statement.setString(2, snapshotId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "no stored payload for " + snapshotId);
                    return result.getBytes(1);
                }
            }
        });
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = PostgresSnapshotFormatCompatibilityTest.class.getResourceAsStream(FIXTURES + name)) {
            if (input == null) throw new IOException("missing fixture " + name);
            return input.readAllBytes();
        }
    }
}
