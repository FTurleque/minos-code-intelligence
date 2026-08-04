package com.minos.storage.postgresql;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresCodeKnowledgeSnapshotStoreTest extends PostgresTestSupport {

    @Test
    void publishesAndLoadsActiveKnowledgeSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections);
        CodeKnowledgeSnapshot snapshot = snapshot(projectId, "snap-1",
                symbol(projectId, "sym-a"), symbol(projectId, "sym-b"));

        store.publish(projectId, "snap-1", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        Optional<CodeKnowledgeSnapshot> loaded = store.loadActiveKnowledge(projectId);

        assertTrue(loaded.isPresent());
        assertEquals(snapshot, loaded.get());
    }

    @Test
    void activePointerFollowsLatestPublish() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections);
        CodeKnowledgeSnapshot first = snapshot(projectId, "snap-1", symbol(projectId, "sym-a"));
        CodeKnowledgeSnapshot second = snapshot(projectId, "snap-2", symbol(projectId, "sym-b"));

        store.publish(projectId, "snap-1", first.symbols(), first.occurrences(), first.relationships());
        store.publish(projectId, "snap-2", second.symbols(), second.occurrences(), second.relationships());

        Optional<CodeKnowledgeSnapshot> loaded = store.loadActiveKnowledge(projectId);
        assertTrue(loaded.isPresent());
        assertEquals("snap-2", loaded.get().snapshotId());
    }

    @Test
    void isIdempotentForSameContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections);
        CodeKnowledgeSnapshot snapshot = snapshot(projectId, "snap-1", symbol(projectId, "sym-a"));

        store.publish(projectId, "snap-1", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        store.publish(projectId, "snap-1", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
    }

    @Test
    void rejectsIdentityMutationWithDifferentContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")), List.of(), List.of());

        IOException exception = assertThrows(IOException.class,
                () -> store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-b")), List.of(), List.of()));
        assertTrue(exception.getMessage().contains("different content"),
                "exception must describe the identity conflict: " + exception.getMessage());
    }

    @Test
    void returnsEmptyForUnknownProject() throws Exception {
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections);
        assertTrue(store.loadActiveKnowledge(UUID.randomUUID()).isEmpty());
    }

    @Test
    void detectsChecksumMismatchBeforeDeserializing() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")), List.of(), List.of());

        try (var c = connections.open();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE knowledge_snapshots SET payload = decode('deadbeef', 'hex') " +
                             "WHERE project_id=? AND snapshot_id=?")) {
            s.setObject(1, projectId);
            s.setString(2, "snap-1");
            s.executeUpdate();
        }

        IOException exception = assertThrows(IOException.class,
                () -> store.loadActiveKnowledge(projectId));
        assertTrue(exception.getMessage().contains("checksum mismatch"),
                "exception must describe the corruption: " + exception.getMessage());
    }

    private static CodeKnowledgeSnapshot snapshot(UUID projectId, String snapshotId, Symbol... symbols) {
        return new CodeKnowledgeSnapshot(projectId, snapshotId, List.of(symbols), List.of(), List.of());
    }

    static Symbol symbol(UUID projectId, String id) {
        return new Symbol(
                id,
                projectId + "|java|METHOD|com.example.Service." + id + "|" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                "main", "Service.java", null,
                SymbolKind.METHOD, id, "com.example.Service." + id, "()", "java",
                new SymbolLocation("Service.java", 10, 1, 10, 50, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                new Origin("test-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                false, false,
                Set.of(new ProviderReference("test-provider", "ext-" + id))
        );
    }
}
