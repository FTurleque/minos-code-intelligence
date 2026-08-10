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
import java.util.Iterator;
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
 * <p>Binary encoding, active-pointer state and retention remain delegated to dedicated components.
 * The in-process query cache is LRU-bounded by both entry count and estimated retained memory.</p>
 */
public final class FileSymbolSnapshotStore implements CodeKnowledgeSnapshotStore {

    public static final int DEFAULT_MAX_QUERY_CACHE_ENTRIES = 32;
    public static final long DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES = 512L * 1024L * 1024L;
    public static final int DEFAULT_MAX_ACTIVE_QUERY_RETRIES = 2;
    private static final int BUILD_LOCK_STRIPES = 64;

    private final Path storageRoot;
    private final SnapshotRepository snapshotRepository;
    private final ActiveSnapshotRepository activeSnapshotRepository;
    private final SnapshotIntegrityService integrityService;
    private final SnapshotRetentionService retentionService;
    private final SnapshotCodec codecV1;
    private final SnapshotCodec codecV2;
    private final int maxQueryCacheEntries;
    private final long maxQueryCacheWeightBytes;
    private final int maxActiveQueryRetries;
    private final ActiveQueryBuildHook activeQueryBuildHook;
    private final Object cacheLock = new Object();
    private final ReentrantLock[] buildLocks = buildLocks();
    private final LinkedHashMap<CacheKey, WeightedQueryView> queryCache =
            new LinkedHashMap<>(16, 0.75f, true);

    private long cacheHits;
    private long cacheMisses;
    private long fullSnapshotLoads;
    private long queryViewBuilds;
    private long cacheWeightBytes;
    private long cacheEvictions;

    public FileSymbolSnapshotStore(Path storageRoot) throws IOException {
        this(storageRoot, DEFAULT_MAX_QUERY_CACHE_ENTRIES, DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES);
    }

    FileSymbolSnapshotStore(Path storageRoot, int maxQueryCacheEntries) throws IOException {
        this(storageRoot, maxQueryCacheEntries, DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES);
    }

    FileSymbolSnapshotStore(Path storageRoot, int maxQueryCacheEntries, long maxQueryCacheWeightBytes) throws IOException {
        this(
                storageRoot,
                maxQueryCacheEntries,
                maxQueryCacheWeightBytes,
                DEFAULT_MAX_ACTIVE_QUERY_RETRIES,
                ActiveQueryBuildHook.NOOP);
    }

    FileSymbolSnapshotStore(
            Path storageRoot,
            int maxQueryCacheEntries,
            long maxQueryCacheWeightBytes,
            int maxActiveQueryRetries,
            ActiveQueryBuildHook activeQueryBuildHook
    ) throws IOException {
        if (maxQueryCacheEntries < 1) {
            throw new IllegalArgumentException("maxQueryCacheEntries must be greater than zero");
        }
        if (maxQueryCacheWeightBytes < 1L) {
            throw new IllegalArgumentException("maxQueryCacheWeightBytes must be greater than zero");
        }
        if (maxActiveQueryRetries < 0) {
            throw new IllegalArgumentException("maxActiveQueryRetries must not be negative");
        }
        this.snapshotRepository = new SnapshotRepository(storageRoot);
        this.storageRoot = snapshotRepository.storageRoot();
        this.activeSnapshotRepository = new ActiveSnapshotRepository(snapshotRepository);
        this.integrityService = new SnapshotIntegrityService();
        this.retentionService = new SnapshotRetentionService(snapshotRepository);
        this.codecV1 = new SnapshotCodecV1();
        this.codecV2 = new SnapshotCodecV2();
        this.maxQueryCacheEntries = maxQueryCacheEntries;
        this.maxQueryCacheWeightBytes = maxQueryCacheWeightBytes;
        this.maxActiveQueryRetries = maxActiveQueryRetries;
        this.activeQueryBuildHook = Objects.requireNonNull(activeQueryBuildHook, "activeQueryBuildHook");
    }

    @Override
    public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(symbols, "symbols");
        List<Symbol> orderedSymbols = orderedById(symbols, Symbol::id, "symbols");
        SymbolSnapshot legacy = new SymbolSnapshot(projectId, snapshotId, orderedSymbols);
        rejectDuplicateIds(orderedSymbols, Symbol::id, "symbol");
        publishSnapshot(new CodeKnowledgeSnapshot(projectId, snapshotId, orderedSymbols, List.of(), List.of()), codecV1);
        return legacy;
    }

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
        List<SymbolOccurrence> orderedOccurrences = orderedById(occurrences, SymbolOccurrence::id, "occurrences");
        List<Relationship> orderedRelationships = orderedById(relationships, Relationship::id, "relationships");
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId, snapshotId, orderedSymbols, orderedOccurrences, orderedRelationships);
        rejectDuplicateIds(orderedSymbols, Symbol::id, "symbol");
        rejectDuplicateIds(orderedOccurrences, SymbolOccurrence::id, "occurrence");
        rejectDuplicateIds(orderedRelationships, Relationship::id, "relationship");
        publishSnapshot(snapshot, codecV2);
        return snapshot;
    }

    @Override
    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(snapshot -> new SymbolSnapshot(
                snapshot.projectId(), snapshot.snapshotId(), snapshot.symbols()));
    }

    @Override
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        return loadActiveQueryView(projectId).map(SnapshotQueryView::snapshot);
    }

    @Override
    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        int attempts = 0;
        while (true) {
            attempts++;
            QueryResolution resolution = resolveActiveQueryView(projectId);
            if (!resolution.shouldRetry()) {
                return resolution.view();
            }
            if (attempts > maxActiveQueryRetries) {
                throw new IOException(
                        "active symbol snapshot changed repeatedly; retry limit exceeded for project "
                                + projectId + " after " + attempts + " attempts");
            }
        }
    }

    private QueryResolution resolveActiveQueryView(UUID projectId) throws IOException {
        Optional<SnapshotDescriptor> active = activeSnapshotRepository.read(projectId);
        if (active.isEmpty()) return QueryResolution.complete(Optional.empty());
        SnapshotDescriptor descriptor = active.orElseThrow();
        CacheKey key = new CacheKey(projectId, descriptor.snapshotId());
        Optional<SnapshotQueryView> cached = cachedView(key, descriptor);
        if (cached.isPresent()) return QueryResolution.complete(cached);
        return buildUnderProjectLock(projectId, descriptor, key);
    }

    private QueryResolution buildUnderProjectLock(UUID projectId, SnapshotDescriptor descriptor, CacheKey key)
            throws IOException {
        ReentrantLock buildLock = buildLock(projectId);
        buildLock.lock();
        try {
            Optional<SnapshotQueryView> cached = cachedView(key, descriptor);
            if (cached.isPresent()) return QueryResolution.complete(cached);
            recordCacheMiss();
            PointerState beforeBuild = pointerState(projectId, descriptor);
            if (beforeBuild == PointerState.MISSING) return QueryResolution.complete(Optional.empty());
            if (beforeBuild == PointerState.CHANGED) return QueryResolution.retryResolution();
            SnapshotQueryView built = buildQueryView(projectId, descriptor);
            activeQueryBuildHook.afterBuild(projectId, descriptor);
            PointerState afterBuild = pointerState(projectId, descriptor);
            if (afterBuild == PointerState.MISSING) return QueryResolution.complete(Optional.empty());
            if (afterBuild == PointerState.CHANGED) return QueryResolution.retryResolution();
            return publishBuiltView(projectId, key, descriptor, built);
        } finally {
            buildLock.unlock();
        }
    }

    private PointerState pointerState(UUID projectId, SnapshotDescriptor expected) throws IOException {
        Optional<SnapshotDescriptor> active = activeSnapshotRepository.read(projectId);
        if (active.isEmpty()) return PointerState.MISSING;
        return expected.equals(active.orElseThrow()) ? PointerState.MATCH : PointerState.CHANGED;
    }

    private Optional<SnapshotQueryView> cachedView(CacheKey key, SnapshotDescriptor descriptor) {
        synchronized (cacheLock) {
            WeightedQueryView cached = queryCache.get(key);
            if (cached != null && cached.view().descriptor().equals(descriptor)) {
                cacheHits++;
                return Optional.of(cached.view());
            }
            return Optional.empty();
        }
    }

    private void recordCacheMiss() {
        synchronized (cacheLock) { cacheMisses++; }
    }

    private QueryResolution publishBuiltView(
            UUID projectId,
            CacheKey key,
            SnapshotDescriptor descriptor,
            SnapshotQueryView built
    ) {
        long weight = estimateQueryViewWeight(descriptor);
        synchronized (cacheLock) {
            WeightedQueryView raced = queryCache.get(key);
            if (raced != null && raced.view().descriptor().equals(descriptor)) {
                cacheHits++;
                return QueryResolution.complete(Optional.of(raced.view()));
            }
            removeProjectEntries(projectId, key);
            if (weight <= maxQueryCacheWeightBytes) {
                queryCache.put(key, new WeightedQueryView(built, weight));
                cacheWeightBytes = safeAdd(cacheWeightBytes, weight);
                trimCache();
            }
            return QueryResolution.complete(Optional.of(built));
        }
    }

    public Path storageRoot() { return storageRoot; }

    public SnapshotRetentionService retentionService() { return retentionService; }

    public CacheStats cacheStats() {
        synchronized (cacheLock) {
            return new CacheStats(
                    cacheHits, cacheMisses, fullSnapshotLoads, queryViewBuilds,
                    queryCache.size(), maxQueryCacheEntries,
                    cacheWeightBytes, maxQueryCacheWeightBytes, cacheEvictions);
        }
    }

    private SnapshotQueryView buildQueryView(UUID projectId, SnapshotDescriptor descriptor) throws IOException {
        Path snapshotFile = snapshotRepository.resolveSnapshotFile(projectId, descriptor.fileName());
        if (!Files.isRegularFile(snapshotFile)) {
            throw new IOException("active symbol snapshot file is missing: " + snapshotFile);
        }
        integrityService.verifyChecksum(snapshotFile, descriptor.sha256());
        CodeKnowledgeSnapshot snapshot = codecFor(descriptor.formatVersion()).read(snapshotFile);
        recordFullSnapshotLoad();
        integrityService.verifyMetadata(snapshot, projectId, descriptor);
        long buildStarted = System.nanoTime();
        InMemoryCodeKnowledgeStore indexedStore = new InMemoryCodeKnowledgeStore(snapshot);
        long buildNanos = System.nanoTime() - buildStarted;
        recordQueryViewBuild();
        return new SnapshotQueryView(descriptor, snapshot, indexedStore, buildNanos);
    }

    private void recordFullSnapshotLoad() {
        synchronized (cacheLock) { fullSnapshotLoads++; }
    }

    private void recordQueryViewBuild() {
        synchronized (cacheLock) { queryViewBuilds++; }
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot, SnapshotCodec codec) throws IOException {
        Path temporarySnapshot = snapshotRepository.createTemporarySnapshot(snapshot.projectId());
        try {
            SnapshotCodec.SnapshotEncoding encoding = codec.write(temporarySnapshot, snapshot);
            String fileName = "snapshot-" + integrityService.logicalIdHash(snapshot.snapshotId())
                    + "-" + encoding.sha256() + codec.fileExtension();
            // Publication and active-pointer promotion are one mutation transaction with respect to
            // retention. A compactor can no longer observe the newly published file as historical
            // in the gap before active.pointer is replaced.
            try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, snapshot.projectId())) {
                snapshotRepository.publishSnapshot(snapshot.projectId(), fileName, temporarySnapshot);
                activeSnapshotRepository.promote(
                        snapshot.projectId(),
                        new SnapshotDescriptor(
                                codec.formatVersion(), snapshot.snapshotId(), fileName, encoding.sha256(),
                                encoding.symbolCount(), encoding.occurrenceCount(), encoding.relationshipCount()));
            }
            invalidateProjectCache(snapshot.projectId());
        } finally {
            Files.deleteIfExists(temporarySnapshot);
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

    private ReentrantLock buildLock(UUID projectId) {
        return buildLocks[Math.floorMod(projectId.hashCode(), buildLocks.length)];
    }

    private void trimCache() {
        while (queryCache.size() > maxQueryCacheEntries || cacheWeightBytes > maxQueryCacheWeightBytes) {
            CacheKey eldest = queryCache.keySet().iterator().next();
            WeightedQueryView removed = queryCache.remove(eldest);
            cacheWeightBytes -= removed.weightBytes();
            cacheEvictions++;
        }
    }

    private SnapshotCodec codecFor(int version) throws IOException {
        return switch (version) {
            case ActiveSnapshotRepository.FORMAT_VERSION_V1 -> codecV1;
            case ActiveSnapshotRepository.FORMAT_VERSION_V2 -> codecV2;
            default -> throw new IOException("unsupported active snapshot pointer version: " + version);
        };
    }

    private static long estimateQueryViewWeight(SnapshotDescriptor descriptor) {
        long weight = 64L * 1024L;
        weight = safeAdd(weight, (long) descriptor.symbolCount() * 1024L);
        weight = safeAdd(weight, (long) descriptor.occurrenceCount() * 640L);
        weight = safeAdd(weight, (long) descriptor.relationshipCount() * 1024L);
        return weight;
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static <T> List<T> orderedById(Collection<T> values, Function<T, String> id, String name) {
        return values.stream()
                .map(value -> Objects.requireNonNull(value, name + " must not contain null"))
                .sorted(Comparator.comparing(id))
                .toList();
    }

    private static <T> void rejectDuplicateIds(List<T> values, Function<T, String> id, String name) {
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException(fieldName + " must not be blank");
    }

    private enum PointerState { MATCH, CHANGED, MISSING }

    private record QueryResolution(Optional<SnapshotQueryView> view, boolean shouldRetry) {
        private static QueryResolution complete(Optional<SnapshotQueryView> view) { return new QueryResolution(view, false); }
        private static QueryResolution retryResolution() { return new QueryResolution(Optional.empty(), true); }
    }

    private record CacheKey(UUID projectId, String snapshotId) {
        private CacheKey {
            Objects.requireNonNull(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
        }
    }

    private record WeightedQueryView(SnapshotQueryView view, long weightBytes) {
        private WeightedQueryView {
            Objects.requireNonNull(view, "view");
            if (weightBytes < 1L) throw new IllegalArgumentException("weightBytes must be positive");
        }
    }

    public record CacheStats(
            long hits,
            long misses,
            long fullSnapshotLoads,
            long queryViewBuilds,
            int entries,
            int maximumEntries,
            long weightBytes,
            long maximumWeightBytes,
            long evictions
    ) { }
}

@FunctionalInterface
interface ActiveQueryBuildHook {
    ActiveQueryBuildHook NOOP = (projectId, descriptor) -> { };

    void afterBuild(UUID projectId, SnapshotDescriptor descriptor) throws IOException;
}
