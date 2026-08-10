package com.minos.storage.postgresql;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.file.Files;
import java.nio.file.Path;

final class PostgresCodeKnowledgeSnapshotStore implements CodeKnowledgeSnapshotStore {
    private static final long MAX_PERSISTED_SNAPSHOT_BYTES = 3L * 1024L * 1024L * 1024L;
    private static final int MAX_QUERY_CACHE_ENTRIES = 32;
    private static final long MAX_QUERY_CACHE_WEIGHT_BYTES = 512L * 1024L * 1024L;
    private static final int BUILD_LOCK_STRIPES = 64;

    private final PostgresConnectionFactory connections;
    private final PostgresSnapshotPayloadCodec codec = new PostgresSnapshotPayloadCodec();
    private final Object cacheLock = new Object();
    private final LinkedHashMap<CacheKey, WeightedQueryView> queryCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock[] buildLocks = buildLocks();
    private long cacheWeightBytes;
    private long cacheHits;
    private long cacheMisses;
    private long cacheEvictions;

    PostgresCodeKnowledgeSnapshotStore(PostgresConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) throws IOException {
        List<Symbol> ordered = symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId, requireText(snapshotId), ordered, List.of(), List.of());
        publishSnapshot(snapshot);
        return new SymbolSnapshot(projectId, snapshotId, ordered);
    }

    @Override
    public CodeKnowledgeSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols,
            Collection<SymbolOccurrence> occurrences,
            Collection<Relationship> relationships
    ) throws IOException {
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                requireText(snapshotId),
                symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList(),
                occurrences.stream().sorted(Comparator.comparing(SymbolOccurrence::id)).toList(),
                relationships.stream().sorted(Comparator.comparing(Relationship::id)).toList());
        publishSnapshot(snapshot);
        return snapshot;
    }

    @Override
    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveQueryView(projectId).map(value ->
                new SymbolSnapshot(value.snapshot().projectId(), value.snapshot().snapshotId(), value.snapshot().symbols()));
    }

    @Override
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        return loadActiveQueryView(projectId).map(SnapshotQueryView::snapshot);
    }

    @Override
    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Optional<ActiveMetadata> metadataOptional = activeMetadata(projectId);
        if (metadataOptional.isEmpty()) return Optional.empty();
        ActiveMetadata metadata = metadataOptional.orElseThrow();
        CacheKey key = new CacheKey(projectId, metadata.snapshotId(), metadata.sha256());
        Optional<SnapshotQueryView> cached = cached(key);
        if (cached.isPresent()) return cached;

        ReentrantLock buildLock = buildLocks[Math.floorMod(projectId.hashCode(), buildLocks.length)];
        buildLock.lock();
        try {
            cached = cached(key);
            if (cached.isPresent()) return cached;
            synchronized (cacheLock) { cacheMisses++; }

            Optional<Row> row = activeRow(projectId);
            if (row.isEmpty()) return Optional.empty();
            Row value = row.orElseThrow();
            if (!metadata.snapshotId().equals(value.snapshotId()) || !metadata.sha256().equals(value.sha256())) {
                // The active pointer changed between the metadata probe and payload fetch. Retry
                // once against the new authoritative identity rather than caching stale data.
                return loadActiveQueryView(projectId);
            }
            CodeKnowledgeSnapshot snapshot = decodeVerified(projectId, value);
            long started = System.nanoTime();
            InMemoryCodeKnowledgeStore queryStore = new InMemoryCodeKnowledgeStore(snapshot);
            long buildNanos = System.nanoTime() - started;
            SnapshotDescriptor descriptor = new SnapshotDescriptor(
                    2,
                    snapshot.snapshotId(),
                    "postgresql:" + snapshot.snapshotId(),
                    metadata.sha256(),
                    metadata.symbolCount(),
                    metadata.occurrenceCount(),
                    metadata.relationshipCount());
            SnapshotQueryView view = new SnapshotQueryView(descriptor, snapshot, queryStore, buildNanos);
            cache(projectId, key, view, estimateWeight(metadata));
            return Optional.of(view);
        } finally {
            buildLock.unlock();
        }
    }

    QueryCacheStats queryCacheStats() {
        synchronized (cacheLock) {
            return new QueryCacheStats(cacheHits, cacheMisses, cacheEvictions, queryCache.size(),
                    cacheWeightBytes, MAX_QUERY_CACHE_WEIGHT_BYTES);
        }
    }

    private Optional<SnapshotQueryView> cached(CacheKey key) {
        synchronized (cacheLock) {
            WeightedQueryView value = queryCache.get(key);
            if (value == null) return Optional.empty();
            cacheHits++;
            return Optional.of(value.view());
        }
    }

    private void cache(UUID projectId, CacheKey key, SnapshotQueryView view, long weight) {
        synchronized (cacheLock) {
            removeProjectEntries(projectId, key);
            if (weight > MAX_QUERY_CACHE_WEIGHT_BYTES) return;
            WeightedQueryView previous = queryCache.put(key, new WeightedQueryView(view, weight));
            if (previous != null) cacheWeightBytes -= previous.weightBytes();
            cacheWeightBytes = safeAdd(cacheWeightBytes, weight);
            Iterator<Map.Entry<CacheKey, WeightedQueryView>> iterator = queryCache.entrySet().iterator();
            while ((queryCache.size() > MAX_QUERY_CACHE_ENTRIES || cacheWeightBytes > MAX_QUERY_CACHE_WEIGHT_BYTES)
                    && iterator.hasNext()) {
                Map.Entry<CacheKey, WeightedQueryView> eldest = iterator.next();
                cacheWeightBytes -= eldest.getValue().weightBytes();
                iterator.remove();
                cacheEvictions++;
            }
        }
    }

    private void invalidateProjectCache(UUID projectId) {
        synchronized (cacheLock) { removeProjectEntries(projectId, null); }
    }

    private void removeProjectEntries(UUID projectId, CacheKey except) {
        Iterator<Map.Entry<CacheKey, WeightedQueryView>> iterator = queryCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, WeightedQueryView> entry = iterator.next();
            if (entry.getKey().projectId().equals(projectId) && !entry.getKey().equals(except)) {
                cacheWeightBytes -= entry.getValue().weightBytes();
                iterator.remove();
            }
        }
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot) throws IOException {
        Path payload = Files.createTempFile("minos-postgresql-snapshot-", ".knowledge");
        try {
            String sha = codec.encode(payload, snapshot).sha256();
            long payloadBytes = Files.size(payload);
            if (payloadBytes < 1L || payloadBytes > MAX_PERSISTED_SNAPSHOT_BYTES) {
                throw new IOException("PostgreSQL knowledge snapshot payload exceeds streaming limit");
            }
            try {
                connections.inTransaction(connection -> {
                    String existingSha = existingSnapshotSha(connection, snapshot);
                    validateExistingSnapshot(snapshot, sha, existingSha);
                    if (existingSha == null) insertSnapshot(connection, snapshot, payload, payloadBytes, sha);
                    activateSnapshot(connection, snapshot);
                    return null;
                });
                invalidateProjectCache(snapshot.projectId());
            } catch (SQLException exception) {
                throw new IOException("unable to publish PostgreSQL knowledge snapshot", exception);
            }
        } finally {
            Files.deleteIfExists(payload);
        }
    }

    private static String existingSnapshotSha(Connection connection, CodeKnowledgeSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sha256 FROM knowledge_snapshots WHERE project_id=? AND snapshot_id=?")) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void validateExistingSnapshot(
            CodeKnowledgeSnapshot snapshot,
            String expectedSha,
            String existingSha
    ) throws IOException {
        if (existingSha != null && !existingSha.equals(expectedSha)) {
            throw new IOException(
                    "PostgreSQL snapshot identity already exists with different content: "
                            + snapshot.snapshotId());
        }
    }

    private static void insertSnapshot(
            Connection connection,
            CodeKnowledgeSnapshot snapshot,
            Path payload,
            long payloadBytes,
            String sha
    ) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO knowledge_snapshots(project_id,snapshot_id,payload,sha256,symbol_count,"
                        + "occurrence_count,relationship_count) VALUES (?,?,?,?,?,?,?)");
             InputStream input = Files.newInputStream(payload)) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            statement.setBinaryStream(3, input, payloadBytes);
            statement.setString(4, sha);
            statement.setInt(5, snapshot.symbols().size());
            statement.setInt(6, snapshot.occurrences().size());
            statement.setInt(7, snapshot.relationships().size());
            statement.executeUpdate();
        }
    }

    private static void activateSnapshot(Connection connection, CodeKnowledgeSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO knowledge_active(project_id,snapshot_id) VALUES (?,?) "
                        + "ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id")) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            statement.executeUpdate();
        }
    }

    private Optional<ActiveMetadata> activeMetadata(UUID projectId) throws IOException {
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.snapshot_id,s.sha256,s.symbol_count,s.occurrence_count,s.relationship_count "
                                + "FROM knowledge_active a JOIN knowledge_snapshots s "
                                + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id "
                                + "WHERE a.project_id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        return Optional.of(new ActiveMetadata(
                                result.getString(1), result.getString(2),
                                result.getInt(3), result.getInt(4), result.getInt(5)));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to read PostgreSQL active snapshot metadata", exception);
        }
    }

    private Optional<Row> activeRow(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.snapshot_id,s.payload,s.sha256 FROM knowledge_active a "
                                + "JOIN knowledge_snapshots s "
                                + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id "
                                + "WHERE a.project_id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        Path payload = Files.createTempFile("minos-postgresql-snapshot-read-", ".knowledge");
                        try (InputStream input = result.getBinaryStream(2);
                             OutputStream output = Files.newOutputStream(payload)) {
                            copyBounded(input, output, MAX_PERSISTED_SNAPSHOT_BYTES);
                        } catch (Exception exception) {
                            Files.deleteIfExists(payload);
                            throw exception;
                        }
                        return Optional.of(new Row(result.getString(1), payload, result.getString(3)));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to load PostgreSQL knowledge snapshot", exception);
        }
    }

    private CodeKnowledgeSnapshot decodeVerified(UUID projectId, Row row) throws IOException {
        try {
            String actualSha = new com.minos.store.SnapshotIntegrityService().checksum(row.payload());
            if (!row.sha256().equals(actualSha)) {
                throw new IOException("PostgreSQL knowledge snapshot checksum mismatch");
            }
            CodeKnowledgeSnapshot snapshot = codec.decode(row.payload());
            if (!projectId.equals(snapshot.projectId()) || !row.snapshotId().equals(snapshot.snapshotId())) {
                throw new IOException("PostgreSQL knowledge snapshot identity mismatch");
            }
            return snapshot;
        } finally {
            Files.deleteIfExists(row.payload());
        }
    }

    private static void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            try {
                total = Math.addExact(total, read);
            } catch (ArithmeticException exception) {
                throw new IOException("PostgreSQL knowledge snapshot payload size overflow", exception);
            }
            if (total > maximum) throw new IOException("PostgreSQL knowledge snapshot payload exceeds streaming limit");
            output.write(buffer, 0, read);
        }
    }

    private static long estimateWeight(ActiveMetadata metadata) {
        long weight = 64L * 1024L;
        weight = safeAdd(weight, (long) metadata.symbolCount() * 1024L);
        weight = safeAdd(weight, (long) metadata.occurrenceCount() * 640L);
        weight = safeAdd(weight, (long) metadata.relationshipCount() * 1024L);
        return weight;
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static ReentrantLock[] buildLocks() {
        ReentrantLock[] locks = new ReentrantLock[BUILD_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return value;
    }

    private record Row(String snapshotId, Path payload, String sha256) {
        private Row {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private record ActiveMetadata(String snapshotId, String sha256, int symbolCount,
                                  int occurrenceCount, int relationshipCount) { }
    private record CacheKey(UUID projectId, String snapshotId, String sha256) { }
    private record WeightedQueryView(SnapshotQueryView view, long weightBytes) { }
    record QueryCacheStats(long hits, long misses, long evictions, int entries,
                           long weightBytes, long maximumWeightBytes) { }
}
