package com.minos.storage.postgresql;

import com.minos.diagnostics.PublicErrorMessages;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresCodeKnowledgeSnapshotStoreTest extends PostgresTestSupport {

    @TempDir Path tempDir;

    @Test
    void publishesAndLoadsActiveKnowledgeSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
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
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        CodeKnowledgeSnapshot first = snapshot(projectId, "snap-1", symbol(projectId, "sym-a"));
        CodeKnowledgeSnapshot second = snapshot(projectId, "snap-2", symbol(projectId, "sym-b"));

        store.publish(projectId, "snap-1", first.symbols(), first.occurrences(), first.relationships());
        store.publish(projectId, "snap-2", second.symbols(), second.occurrences(), second.relationships());

        Optional<CodeKnowledgeSnapshot> loaded = store.loadActiveKnowledge(projectId);
        assertTrue(loaded.isPresent());
        assertEquals("snap-2", loaded.get().snapshotId());
    }

    @Test
    void refusesABlankSnapshotIdWithItsNameBeforeTouchingTheDatabase() throws Exception {
        // Q13 : message historique de la copie PostgreSQL de requireText, conserve par la mutualisation.
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);

        IllegalArgumentException shortForm = assertThrows(IllegalArgumentException.class,
                () -> store.publish(projectId, " ", List.of(symbol(projectId, "sym-a"))));
        IllegalArgumentException longForm = assertThrows(IllegalArgumentException.class,
                () -> store.publish(projectId, null, List.of(), List.of(), List.of()));

        assertEquals("snapshotId must not be blank", shortForm.getMessage());
        assertEquals("snapshotId must not be blank", longForm.getMessage());
    }

    @Test
    void isIdempotentForSameContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        CodeKnowledgeSnapshot snapshot = snapshot(projectId, "snap-1", symbol(projectId, "sym-a"));

        store.publish(projectId, "snap-1", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        store.publish(projectId, "snap-1", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
    }

    @Test
    void rejectsIdentityMutationWithDifferentContent() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")), List.of(), List.of());

        IOException exception = assertThrows(IOException.class,
                () -> store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-b")), List.of(), List.of()));
        assertTrue(exception.getMessage().contains("different content"),
                "exception must describe the identity conflict: " + exception.getMessage());
    }

    @Test
    void returnsEmptyForUnknownProject() throws Exception {
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        assertTrue(store.loadActiveKnowledge(UUID.randomUUID()).isEmpty());
    }

    @Test
    void detectsChecksumMismatchBeforeDeserializing() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")), List.of(), List.of());

        connections.withConnection(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE knowledge_snapshots SET payload = decode('deadbeef', 'hex') " +
                            "WHERE project_id=? AND snapshot_id=?")) {
                s.setObject(1, projectId);
                s.setString(2, "snap-1");
                s.executeUpdate();
                return null;
            }
        });

        IOException exception = assertThrows(IOException.class,
                () -> store.loadActiveKnowledge(projectId));
        assertTrue(exception.getMessage().contains("checksum mismatch"),
                "exception must describe the corruption: " + exception.getMessage());
    }

    @Test
    void oversizedSnapshotIsRefusedBeforeAnyScratchFileOrRow() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "small", List.of(symbol(projectId, "sym-a")), List.of(), List.of());
        String large = "s".repeat(8_000_000);
        List<Symbol> symbols = IntStream.range(0, 34).mapToObj(index -> symbol(projectId, "big-" + index, large)).toList();

        IOException refused = assertThrows(IOException.class,
                () -> store.publish(projectId, "oversized", symbols, List.of(), List.of()));

        String message = refused.getMessage();
        assertTrue(message.startsWith("knowledge snapshot is too large to persist: ")
                && message.endsWith(" encoded bytes exceed the 268435456-byte limit (256 MiB); nothing was written"), message);
        assertEquals(message, PublicErrorMessages.sanitize(message, "fallback"));
        try (Stream<Path> scratch = Files.list(tempDir.resolve("postgresql-snapshot-scratch"))) {
            assertEquals(0L, scratch.count(), "no scratch payload may be written");
        }
        assertEquals(1L, (long) connections.withConnection(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM knowledge_snapshots WHERE project_id=?")) {
                s.setObject(1, projectId);
                try (ResultSet result = s.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        }));
        assertEquals("small", store.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

    private static CodeKnowledgeSnapshot snapshot(UUID projectId, String snapshotId, Symbol... symbols) {
        return new CodeKnowledgeSnapshot(projectId, snapshotId, List.of(symbols), List.of(), List.of());
    }

    static Symbol symbol(UUID projectId, String id) {
        return symbol(projectId, id, "()");
    }

    static Symbol symbol(UUID projectId, String id, String signature) {
        return new Symbol(
                id,
                projectId + "|java|METHOD|com.example.Service." + id + "|" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                "main", "Service.java", null,
                SymbolKind.METHOD, id, "com.example.Service." + id, signature, "java",
                new SymbolLocation("Service.java", 10, 1, 10, 50, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                new Origin("test-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                false, false,
                Set.of(new ProviderReference("test-provider", "ext-" + id))
        );
    }
}
