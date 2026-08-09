package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Compatibility facade for local versioned snapshot persistence and active query views.
 *
 * <p>M15-S6 keeps the historical public API and on-disk formats while delegating
 * binary encoding, file publication, active-pointer state, integrity checks and
 * retention to dedicated components. M15-S7/S8 add a bounded in-process cache of
 * immutable, indexed query views keyed by {@code (projectId, snapshotId)}.</p>
 */
public final class FileSymbolSnapshotStore implements CodeKnowledgeSnapshotStore {

    /** Entry-count bound. Snapshot sizes remain measured separately for M16 sizing decisions. */
    public static final int DEFAULT_MAX_QUERY_CACHE_ENTRIES = 32;
    private static final int BUILD_LOCK_STRIPES = 64;

    private final Path storageRoot;
    private final SnapshotRepository snapshotRepository;
    private final ActiveSnapshotRepository activeSnapshotRepository;
    private final SnapshotIntegrityService integrityService;
    private final SnapshotRetentionService retentionService;
    private final SnapshotCodec codecV1;
    private final SnapshotCodec codecV2;
    private final int maxQueryCacheEntries;
    private final Object cacheLock = new Object();
    private final ReentrantLock[] buildLocks = buildLocks();
    private final LinkedHashMap<CacheKey, SnapshotQueryView> queryCache =
            new LinkedHashMap<>(16, 0.75f, true);

    private long cacheHits;
    private long cacheMisses;
    private long fullSnapshotLoads;
    private long queryViewBuilds;

    public FileSymbolSnapshotStore(Path storageRoot) throws IOException {
        this(storageRoot, DEFAULT_MAX_QUERY_CACHE_ENTRIES);
    }

    FileSymbolSnapshotStore(Path storageRoot, int maxQueryCacheEntries) throws IOException {
        if (maxQueryCacheEntries < 1) {
            throw new IllegalArgumentException("maxQueryCacheEntries must be greater than zero");
        }
        this.snapshotRepository = new SnapshotRepository(storageRoot);
        this.storageRoot = snapshotRepository.storageRoot();
        this.activeSnapshotRepository = new ActiveSnapshotRepository(snapshotRepository);
        this.integrityService = new SnapshotIntegrityService();
        this.retentionService = new SnapshotRetentionService(snapshotRepository);
        this.codecV1 = new SnapshotCodecV1();
        this.codecV2 = new SnapshotCodecV2();
        this.maxQueryCacheEntries = maxQueryCacheEntries;
    }

    @Override
    public SymbolSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(symbols, "symbols");

        List<Symbol> orderedSymbols = orderedById(symbols, Symbol::id, "symbols");
        SymbolSnapshot legacy = new SymbolSnapshot(projectId, snapshotId, orderedSymbols);
        rejectDuplicateIds(orderedSymbols, Symbol::id, "symbol");
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                snapshotId,
                orderedSymbols,
                List.of(),
                List.of()
        );
        publishSnapshot(snapshot, codecV1);
        return legacy;
    }

    /** Publishes atomically a complete M3 snapshot using the historical v2 format. */
    @Override
    public CodeKnowledgeSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols,
            Collection<SymbolOccurrence> occurrences,
            Collection<Relationship> relationships
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(occurrences, "occurrences");
        Objects.requireNonNull(relationships, "relationships");

        List<Symbol> orderedSymbols = orderedById(symbols, Symbol::id, "symbols");
        List<SymbolOccurrence> orderedOccurrences = orderedById(
                occurrences,
                SymbolOccurrence::id,
                "occurrences"
        );
        List<Relationship> orderedRelationships = orderedById(
                relationships,
                Relationship::id,
                "relationships"
        );
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                snapshotId,
                orderedSymbols,
                orderedOccurrences,
                orderedRelationships
        );
        rejectDuplicateIds(orderedSymbols, Symbol::id, "symbol");
        rejectDuplicateIds(orderedOccurrences, SymbolOccurrence::id, "occurrence");
        rejectDuplicateIds(orderedRelationships, Relationship::id, "relationship");
        publishSnapshot(snapshot, codecV2);
        return snapshot;
    }

    @Override
    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(snapshot -> new SymbolSnapshot(
                snapshot.projectId(),
                snapshot.snapshotId(),
                snapshot.symbols()
        ));
    }

    /** Loads the active immutable snapshot, reusing the same cached query view when possible. */
    @Override
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        return loadActiveQueryView(projectId).map(SnapshotQueryView::snapshot);
    }

    /**
     * Resolves the active pointer first, then reuses/builds an immutable indexed query view.
     *
     * <p>Correctness does not rely on an invalidation callback: after a miss the pointer is
     * read again before publication of the view. If promotion happened concurrently, the
     * build is discarded and resolution restarts from the new active descriptor.</p>
     */
    @Override
    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        while (true) {
            Optional<SnapshotDescriptor> active = activeSnapshotRepository.read(projectId);
            if (active.isEmpty()) return Optional.empty();
            SnapshotDescriptor descriptor = active.orElseThrow();
            CacheKey key = new CacheKey(projectId, descriptor.snapshotId());

            synchronized (cacheLock) {
                SnapshotQueryView cached = queryCache.get(key);
                if (cached != null && cached.descriptor().equals(descriptor)) {
                    cacheHits++;
                    return Optional.of(cached);
                }
            }

            ReentrantLock buildLock = buildLock(projectId);
            buildLock.lock();
            try {
                synchronized (cacheLock) {
                    SnapshotQueryView cached = queryCache.get(key);
                    if (cached != null && cached.descriptor().equals(descriptor)) {
                        cacheHits++;
                        return Optional.of(cached);
                    }
                    cacheMisses++;
                }

                Optional<SnapshotDescriptor> beforeBuild = activeSnapshotRepository.read(projectId);
                if (beforeBuild.isEmpty()) return Optional.empty();
                if (!descriptor.equals(beforeBuild.orElseThrow())) continue;

                SnapshotQueryView built = buildQueryView(projectId, descriptor);
                Optional<SnapshotDescriptor> afterBuild = activeSnapshotRepository.read(projectId);
                if (afterBuild.isEmpty()) return Optional.empty();
                if (!descriptor.equals(afterBuild.orElseThrow())) continue;

                synchronized (cacheLock) {
                    SnapshotQueryView raced = queryCache.get(key);
                    if (raced != null && raced.descriptor().equals(descriptor)) {
                        cacheHits++;
                        return Optional.of(raced);
                    }
                    queryCache.entrySet().removeIf(entry ->
                            entry.getKey().projectId().equals(projectId) && !entry.getKey().equals(key));
                    queryCache.put(key, built);
                    trimCache();
                    return Optional.of(built);
                }
            } finally {
                buildLock.unlock();
            }
        }
    }

    public Path storageRoot() {
        return storageRoot;
    }

    /** Explicit retention mechanism; automatic retention policy remains deferred to M16 measurements. */
    public SnapshotRetentionService retentionService() {
        return retentionService;
    }

    /** Exposes bounded-cache measurements for qualification and M16 sizing. */
    public CacheStats cacheStats() {
        synchronized (cacheLock) {
            return new CacheStats(
                    cacheHits,
                    cacheMisses,
                    fullSnapshotLoads,
                    queryViewBuilds,
                    queryCache.size(),
                    maxQueryCacheEntries
            );
        }
    }

    private SnapshotQueryView buildQueryView(UUID projectId, SnapshotDescriptor descriptor) throws IOException {
        Path snapshotFile = snapshotRepository.resolveSnapshotFile(projectId, descriptor.fileName());
        if (!Files.isRegularFile(snapshotFile)) {
            throw new IOException("active symbol snapshot file is missing: " + snapshotFile);
        }
        integrityService.verifyChecksum(snapshotFile, descriptor.sha256());

        CodeKnowledgeSnapshot snapshot = codecFor(descriptor.formatVersion()).read(snapshotFile);
        synchronized (cacheLock) { fullSnapshotLoads++; }
        integrityService.verifyMetadata(snapshot, projectId, descriptor);

        long buildStarted = System.nanoTime();
        InMemoryCodeKnowledgeStore indexedStore = new InMemoryCodeKnowledgeStore(snapshot);
        long buildNanos = System.nanoTime() - buildStarted;
        synchronized (cacheLock) { queryViewBuilds++; }
        return new SnapshotQueryView(descriptor, snapshot, indexedStore, buildNanos);
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot, SnapshotCodec codec) throws IOException {
        Path temporarySnapshot = snapshotRepository.createTemporarySnapshot(snapshot.projectId());
        try {
            SnapshotCodec.SnapshotEncoding encoding = codec.write(temporarySnapshot, snapshot);
            String fileName = "snapshot-"
                    + integrityService.logicalIdHash(snapshot.snapshotId())
                    + "-"
                    + encoding.sha256()
                    + codec.fileExtension();
            snapshotRepository.publishSnapshot(snapshot.projectId(), fileName, temporarySnapshot);
            activeSnapshotRepository.promote(
                    snapshot.projectId(),
                    new SnapshotDescriptor(
                            codec.formatVersion(),
                            snapshot.snapshotId(),
                            fileName,
                            encoding.sha256(),
                            encoding.symbolCount(),
                            encoding.occurrenceCount(),
                            encoding.relationshipCount()
                    )
            );
            invalidateProjectCache(snapshot.projectId());
        } finally {
            Files.deleteIfExists(temporarySnapshot);
        }
    }

    private void invalidateProjectCache(UUID projectId) {
        synchronized (cacheLock) {
            queryCache.entrySet().removeIf(entry -> entry.getKey().projectId().equals(projectId));
        }
    }

    private ReentrantLock buildLock(UUID projectId) {
        return buildLocks[Math.floorMod(projectId.hashCode(), buildLocks.length)];
    }

    private void trimCache() {
        while (queryCache.size() > maxQueryCacheEntries) {
            CacheKey eldest = queryCache.keySet().iterator().next();
            queryCache.remove(eldest);
        }
    }

    private SnapshotCodec codecFor(int version) throws IOException {
        return switch (version) {
            case ActiveSnapshotRepository.FORMAT_VERSION_V1 -> codecV1;
            case ActiveSnapshotRepository.FORMAT_VERSION_V2 -> codecV2;
            default -> throw new IOException("unsupported active snapshot pointer version: " + version);
        };
    }

    private static <T> List<T> orderedById(
            Collection<T> values,
            Function<T, String> id,
            String name
    ) {
        return values.stream()
                .map(value -> Objects.requireNonNull(value, name + " must not contain null"))
                .sorted(Comparator.comparing(id))
                .toList();
    }

    private static <T> void rejectDuplicateIds(
            List<T> values,
            Function<T, String> id,
            String name
    ) {
        Set<String> ids = new HashSet<>();
        for (T value : values) {
            String currentId = id.apply(value);
            if (!ids.add(currentId)) {
                throw new IllegalArgumentException("duplicate " + name + " id in snapshot: " + currentId);
            }
        }
    }

    private static ReentrantLock[] buildLocks() {
        ReentrantLock[] locks = new ReentrantLock[BUILD_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private record CacheKey(UUID projectId, String snapshotId) {
        private CacheKey {
            Objects.requireNonNull(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
        }
    }

    public record CacheStats(
            long hits,
            long misses,
            long fullSnapshotLoads,
            long queryViewBuilds,
            int entries,
            int maximumEntries
    ) {
    }
}
