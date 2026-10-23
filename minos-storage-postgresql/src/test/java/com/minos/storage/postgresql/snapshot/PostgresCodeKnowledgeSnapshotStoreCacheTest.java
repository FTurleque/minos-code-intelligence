package com.minos.storage.postgresql.snapshot;

import com.minos.domain.Symbol;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.store.IncomingRelationships;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SnapshotReadView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.minos.storage.postgresql.snapshot.PostgresCodeKnowledgeSnapshotStoreTest.symbol;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Query-view cache, scratch directory and identity checks of the PostgreSQL knowledge store: what is served
 * from memory, what invalidates it, and what is refused instead of being decoded or written.
 */
class PostgresCodeKnowledgeSnapshotStoreCacheTest extends PostgresTestSupport {
    private static final String SCRATCH_DIRECTORY = "postgresql-snapshot-scratch";

    @TempDir Path tempDir;

    @Test
    void aSymbolOnlyPublicationIsStoredSortedAndDescribedByItsQueryView() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);

        SymbolSnapshot published = store.publish(projectId, "snap-1",
                List.of(symbol(projectId, "sym-b"), symbol(projectId, "sym-a")));

        assertEquals(List.of("sym-a", "sym-b"), published.symbols().stream().map(Symbol::id).toList());
        assertEquals(Optional.of(published), store.loadActive(projectId));
        SnapshotQueryView view = store.loadActiveQueryView(projectId).orElseThrow();
        assertEquals("snap-1", view.descriptor().snapshotId());
        assertEquals("postgresql:snap-1", view.descriptor().fileName());
        assertEquals(storedSha(projectId, "snap-1"), view.descriptor().sha256());
        assertEquals(2, view.descriptor().symbolCount());
        assertEquals(0, view.descriptor().occurrenceCount());
        assertEquals(0, view.descriptor().relationshipCount());
        assertEquals(published.symbols(), view.snapshot().symbols());
        assertEquals(Optional.empty(), store.loadActive(UUID.randomUUID()));
        assertEquals(Optional.empty(), store.loadActiveQueryView(UUID.randomUUID()));
        assertEquals(0L, scratchFiles(tempDir), "no decoded payload may be left on disk");
    }

    @Test
    void theActiveQueryViewIsBuiltOnceThenServedFromTheCache() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        assertEquals(0, store.queryCacheStats().entries());

        SnapshotQueryView built = store.loadActiveQueryView(projectId).orElseThrow();
        PostgresCodeKnowledgeSnapshotStore.QueryCacheStats afterBuild = store.queryCacheStats();
        SnapshotQueryView served = store.loadActiveQueryView(projectId).orElseThrow();
        PostgresCodeKnowledgeSnapshotStore.QueryCacheStats afterHit = store.queryCacheStats();

        assertSame(built, served);
        assertEquals(0L, afterBuild.hits());
        assertEquals(1L, afterBuild.misses());
        assertEquals(1, afterBuild.entries());
        assertTrue(afterBuild.weightBytes() > 0L && afterBuild.weightBytes() <= afterBuild.maximumWeightBytes(),
                "weight " + afterBuild.weightBytes());
        assertEquals(1L, afterHit.hits());
        assertEquals(1L, afterHit.misses());
        assertEquals(0L, afterHit.evictions());
        assertEquals(afterBuild.weightBytes(), afterHit.weightBytes());
    }

    /** AUD-ARC-07 : la vue neutre est l'objet en cache ; l'ancien contrat partage sa construction et son index. */
    @Test
    @SuppressWarnings("deprecation")
    void theReadViewAndTheLegacyViewShareOneBuildAndOneIncomingIndex() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a"), symbol(projectId, "sym-b")));

        SnapshotReadView read = store.loadActiveReadView(projectId).orElseThrow();
        IncomingRelationships byReadView = read.incomingRelationships();
        SnapshotQueryView legacy = store.loadActiveQueryView(projectId).orElseThrow();

        assertSame(read, store.loadActiveReadView(projectId).orElseThrow());
        assertSame(legacy, store.loadActiveQueryView(projectId).orElseThrow());
        assertSame(read.reader(), legacy.queryStore());
        assertSame(read.snapshot(), legacy.snapshot());
        assertSame(byReadView, legacy.queryStore().symbolEdgeIndex(projectId.toString()));
        assertEquals(1L, legacy.queryStore().symbolEdgeIndexBuilds());
        assertEquals(1L, store.queryCacheStats().misses(), "one view built for both contracts");
        assertEquals(3L, store.queryCacheStats().hits());
        assertEquals(1, store.queryCacheStats().entries());
    }

    @Test
    void publishingInvalidatesTheCachedViewOfItsProjectOnly() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        store.publish(otherProject, "other-1", List.of(symbol(otherProject, "sym-o")));
        SnapshotQueryView first = store.loadActiveQueryView(projectId).orElseThrow();
        SnapshotQueryView other = store.loadActiveQueryView(otherProject).orElseThrow();
        assertEquals(2, store.queryCacheStats().entries());

        store.publish(projectId, "snap-2", List.of(symbol(projectId, "sym-b")));

        assertEquals(1, store.queryCacheStats().entries());
        SnapshotQueryView second = store.loadActiveQueryView(projectId).orElseThrow();
        assertNotSame(first, second);
        assertEquals("snap-2", second.descriptor().snapshotId());
        assertSame(other, store.loadActiveQueryView(otherProject).orElseThrow(), "another project keeps its cached view");
        assertEquals(2, store.queryCacheStats().entries());
        assertEquals(0L, store.queryCacheStats().evictions(), "an invalidation is not an eviction");
    }

    @Test
    void aSnapshotActivatedByAnotherStoreReplacesTheStaleCachedView() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore reader = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        PostgresCodeKnowledgeSnapshotStore writer = new PostgresCodeKnowledgeSnapshotStore(
                connections, Files.createDirectories(tempDir.resolve("writer-home")));
        writer.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        assertEquals("snap-1", reader.loadActiveQueryView(projectId).orElseThrow().descriptor().snapshotId());

        writer.publish(projectId, "snap-2", List.of(symbol(projectId, "sym-b")));
        SnapshotQueryView refreshed = reader.loadActiveQueryView(projectId).orElseThrow();

        assertEquals("snap-2", refreshed.descriptor().snapshotId());
        assertEquals(1, reader.queryCacheStats().entries(), "the stale view must not stay in memory");
        assertEquals(2L, reader.queryCacheStats().misses());
        assertSame(refreshed, reader.loadActiveQueryView(projectId).orElseThrow());
    }

    @Test
    void republishingAnOlderSnapshotReactivatesItWithoutStoringItTwice() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        store.publish(projectId, "snap-2", List.of(symbol(projectId, "sym-b")));
        assertEquals("snap-2", store.loadActiveKnowledge(projectId).orElseThrow().snapshotId());

        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));

        assertEquals("snap-1", store.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
        assertEquals(2, storedSnapshots(projectId));
    }

    @Test
    void theCacheIsBoundedAndEvictsTheLeastRecentlyUsedProject() throws Exception {
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        List<UUID> projects = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            UUID projectId = UUID.randomUUID();
            projects.add(projectId);
            store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-" + index)));
            store.loadActiveQueryView(projectId).orElseThrow();
        }

        PostgresCodeKnowledgeSnapshotStore.QueryCacheStats full = store.queryCacheStats();
        assertEquals(32, full.entries());
        assertEquals(1L, full.evictions());
        assertEquals(33L, full.misses());

        store.loadActiveQueryView(projects.getLast()).orElseThrow();
        assertEquals(33L, store.queryCacheStats().misses(), "the most recent project is still cached");
        store.loadActiveQueryView(projects.getFirst()).orElseThrow();
        assertEquals(34L, store.queryCacheStats().misses(), "the oldest project was evicted and is rebuilt");
        assertEquals(32, store.queryCacheStats().entries());
        assertEquals(2L, store.queryCacheStats().evictions());
    }

    @Test
    void aPayloadStoredUnderAnotherProjectOrSnapshotIdentityIsRefusedAndNeverCached() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID impostorProject = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        copySnapshotRow(projectId, "snap-1", impostorProject, "snap-1");
        activate(impostorProject, "snap-1");

        IOException otherProject = assertThrows(IOException.class, () -> store.loadActiveKnowledge(impostorProject));

        copySnapshotRow(projectId, "snap-1", projectId, "snap-renamed");
        activate(projectId, "snap-renamed");
        IOException otherSnapshot = assertThrows(IOException.class, () -> store.loadActiveKnowledge(projectId));

        assertEquals("PostgreSQL knowledge snapshot identity mismatch", otherProject.getMessage());
        assertEquals("PostgreSQL knowledge snapshot identity mismatch", otherSnapshot.getMessage());
        assertEquals(0, store.queryCacheStats().entries());
        assertEquals(0L, scratchFiles(tempDir), "a refused payload must not be left decoded on disk");
    }

    @Test
    void republishingOverACorruptedStoredPayloadIsRefusedInsteadOfComparingGarbage() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE knowledge_snapshots SET payload=decode('deadbeef','hex') WHERE project_id=?")) {
                statement.setObject(1, projectId);
                return statement.executeUpdate();
            }
        });
        List<Symbol> otherContent = List.of(symbol(projectId, "sym-b"));

        IOException refusal = assertThrows(IOException.class, () -> store.publish(projectId, "snap-1", otherContent));

        assertEquals("PostgreSQL knowledge snapshot checksum mismatch", refusal.getMessage());
        assertEquals(1, storedSnapshots(projectId));
        assertEquals(0L, scratchFiles(tempDir));
    }

    @Test
    void aScratchDirectoryThatIsNoLongerARealDirectoryStopsPublicationAndLoading() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        store.publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        Path scratch = tempDir.resolve(SCRATCH_DIRECTORY);
        Files.delete(scratch);
        Files.writeString(scratch, "not a directory");
        List<Symbol> next = List.of(symbol(projectId, "sym-b"));

        IOException publish = assertThrows(IOException.class, () -> store.publish(projectId, "snap-2", next));
        IOException load = assertThrows(IOException.class, () -> store.loadActiveKnowledge(projectId));

        String expected = "PostgreSQL snapshot scratch path must be a real directory under MINOS home";
        assertEquals(expected, publish.getMessage());
        assertEquals(expected, load.getMessage());
        assertEquals("not a directory", Files.readString(scratch), "the foreign file must not be overwritten");
        PostgresCodeKnowledgeSnapshotStore healthy = new PostgresCodeKnowledgeSnapshotStore(
                connections, Files.createDirectories(tempDir.resolve("healthy-home")));
        assertEquals("snap-1", healthy.loadActiveKnowledge(projectId).orElseThrow().snapshotId(),
                "the refused publication must not have changed the active snapshot");
        assertEquals(1, storedSnapshots(projectId));
    }

    @Test
    void aStoreCannotBeCreatedWhenItsScratchPathIsARegularFileOrAnArgumentIsMissing() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("occupied-home"));
        Files.writeString(home.resolve(SCRATCH_DIRECTORY), "not a directory");

        assertThrows(IOException.class, () -> new PostgresCodeKnowledgeSnapshotStore(connections, home));
        assertThrows(NullPointerException.class, () -> new PostgresCodeKnowledgeSnapshotStore(null, tempDir));
        assertThrows(NullPointerException.class, () -> new PostgresCodeKnowledgeSnapshotStore(connections, null));
        assertEquals("not a directory", Files.readString(home.resolve(SCRATCH_DIRECTORY)));
    }

    @Test
    void aStorageFailureIsReportedByOperationAndLeavesNoScratchPayloadBehind() throws Exception {
        PostgresConnectionFactory closed = createFactory("minos");
        closed.close();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(closed, tempDir);
        UUID projectId = UUID.randomUUID();
        List<Symbol> symbols = List.of(symbol(projectId, "sym-a"));

        IOException publish = assertThrows(IOException.class, () -> store.publish(projectId, "snap-1", symbols));
        IOException load = assertThrows(IOException.class, () -> store.loadActiveKnowledge(projectId));
        assertThrows(NullPointerException.class, () -> store.loadActiveQueryView(null));

        assertEquals("unable to publish PostgreSQL knowledge snapshot", publish.getMessage());
        assertEquals("unable to read PostgreSQL active snapshot metadata", load.getMessage());
        assertInstanceOf(SQLException.class, publish.getCause());
        assertInstanceOf(SQLException.class, load.getCause());
        assertEquals(0L, scratchFiles(tempDir), "the encoded payload of a failed publication must be deleted");
        assertEquals(0, storedSnapshots(projectId));
    }

    private void copySnapshotRow(UUID fromProject, String fromSnapshot, UUID toProject, String toSnapshot) throws Exception {
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_snapshots(project_id,snapshot_id,payload,sha256,symbol_count,"
                            + "occurrence_count,relationship_count) "
                            + "SELECT ?,?,payload,sha256,symbol_count,occurrence_count,relationship_count "
                            + "FROM knowledge_snapshots WHERE project_id=? AND snapshot_id=?")) {
                statement.setObject(1, toProject);
                statement.setString(2, toSnapshot);
                statement.setObject(3, fromProject);
                statement.setString(4, fromSnapshot);
                return statement.executeUpdate();
            }
        });
    }

    private void activate(UUID projectId, String snapshotId) throws Exception {
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_active(project_id,snapshot_id) VALUES (?,?) "
                            + "ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id")) {
                statement.setObject(1, projectId);
                statement.setString(2, snapshotId);
                return statement.executeUpdate();
            }
        });
    }

    private String storedSha(UUID projectId, String snapshotId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sha256 FROM knowledge_snapshots WHERE project_id=? AND snapshot_id=?")) {
                statement.setObject(1, projectId);
                statement.setString(2, snapshotId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }

    private int storedSnapshots(UUID projectId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM knowledge_snapshots WHERE project_id=?")) {
                statement.setObject(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private static long scratchFiles(Path home) throws IOException {
        try (Stream<Path> scratch = Files.list(home.resolve(SCRATCH_DIRECTORY))) {
            return scratch.count();
        }
    }
}
