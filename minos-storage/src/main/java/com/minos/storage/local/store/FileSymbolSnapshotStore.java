package com.minos.storage.local.store;

import com.minos.storage.codec.KnowledgeSnapshotCodecs;
import com.minos.storage.codec.SnapshotCodec;
import com.minos.storage.codec.SnapshotCodecV1;
import com.minos.storage.codec.SnapshotIntegrityService;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SnapshotReadView;
import com.minos.store.cache.WeightedLruCache;
import com.minos.store.SymbolSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.minos.domain.Preconditions.requireText;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Compatibility facade for local versioned snapshot persistence and active read views.
 *
 * <p>Binary encoding, active-pointer state and retention remain delegated to dedicated components.
 * The in-process query cache is LRU-bounded by both entry count and estimated retained memory.</p>
 */
public final class FileSymbolSnapshotStore implements CodeKnowledgeSnapshotStore {

    public static final int DEFAULT_MAX_QUERY_CACHE_ENTRIES = 32;
    public static final long DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES = 512L * 1024L * 1024L;
    public static final int DEFAULT_MAX_ACTIVE_QUERY_RETRIES = 2;
    private static final int BUILD_LOCK_STRIPES = 64;
    /**
     * Heap retained by a query view, as a multiple of its persisted size. With one instance per distinct
     * string (ADR 0047, option A) a repository-shaped snapshot retains 1.41 to 1.46 times its V3 file
     * (QueryViewWeightTest); 2 leaves a margin and also bounds a string of two-byte characters, which
     * takes at most twice its UTF-8 size. It was 8 before the strings were shared, when a reread view
     * retained 2.5 to 3.3 times its file.
     */
    private static final long QUERY_VIEW_PERSISTED_AMPLIFICATION = 2L;

    private final Path storageRoot;
    private final SnapshotRepository snapshotRepository;
    private final ActiveSnapshotRepository activeSnapshotRepository;
    private final SnapshotIntegrityService integrityService;
    private final SnapshotRetentionService retentionService;
    private final SnapshotCodec codecV1;
    private final int maxQueryCacheEntries;
    private final long maxQueryCacheWeightBytes;
    private final int maxActiveQueryRetries;
    private final ActiveQueryBuildHook activeQueryBuildHook;
    private final Object cacheLock = new Object();
    private final ReentrantLock[] buildLocks = buildLocks();
    private final WeightedLruCache<CacheKey, SnapshotReadView> queryCache;

    private long cacheHits;
    private long cacheMisses;
    private long fullSnapshotLoads;
    private long queryViewBuilds;

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
        this.maxQueryCacheEntries = maxQueryCacheEntries;
        this.maxQueryCacheWeightBytes = maxQueryCacheWeightBytes;
        this.queryCache = new WeightedLruCache<>(maxQueryCacheEntries, maxQueryCacheWeightBytes);
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
        CodeKnowledgeSnapshot symbolsOnly = new CodeKnowledgeSnapshot(projectId, snapshotId, orderedSymbols, List.of(), List.of());
        publishSnapshot(symbolsOnly, new KnowledgeSnapshotCodecs.Selection(codecV1, codecV1.requirePersistable(symbolsOnly)));
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
        publishSnapshot(snapshot, KnowledgeSnapshotCodecs.select(snapshot));
        return snapshot;
    }

    @Override
    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(snapshot -> new SymbolSnapshot(
                snapshot.projectId(), snapshot.snapshotId(), snapshot.symbols()));
    }

    @Override
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        return loadActiveReadView(projectId).map(SnapshotReadView::snapshot);
    }

    /** The legacy view shares the cached read view and its in-memory index: nothing is built twice. */
    @Deprecated(since = "1.3.0")
    @Override
    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {
        return loadActiveReadView(projectId).map(SnapshotQueryView::of);
    }

    @Override
    public Optional<SnapshotReadView> loadActiveReadView(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        int attempts = 0;
        while (true) {
            attempts++;
            QueryResolution resolution = resolveActiveReadView(projectId);
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

    /** Read from the active pointer alone: the snapshot file is neither opened nor decoded. */
    @Override
    public Optional<String> activeSnapshotId(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        return activeSnapshotRepository.read(projectId).map(SnapshotDescriptor::snapshotId);
    }

    private QueryResolution resolveActiveReadView(UUID projectId) throws IOException {
        Optional<SnapshotDescriptor> active = activeSnapshotRepository.read(projectId);
        if (active.isEmpty()) return QueryResolution.complete(Optional.empty());
        SnapshotDescriptor descriptor = active.orElseThrow();
        CacheKey key = new CacheKey(projectId, descriptor.snapshotId());
        Optional<SnapshotReadView> cached = cachedView(key, descriptor);
        if (cached.isPresent()) return QueryResolution.complete(cached);
        return buildUnderProjectLock(projectId, descriptor, key);
    }

    private QueryResolution buildUnderProjectLock(UUID projectId, SnapshotDescriptor descriptor, CacheKey key)
            throws IOException {
        ReentrantLock buildLock = buildLock(projectId);
        buildLock.lock();
        try {
            Optional<SnapshotReadView> cached = cachedView(key, descriptor);
            if (cached.isPresent()) return QueryResolution.complete(cached);
            recordCacheMiss();
            PointerState beforeBuild = pointerState(projectId, descriptor);
            if (beforeBuild == PointerState.MISSING) return QueryResolution.complete(Optional.empty());
            if (beforeBuild == PointerState.CHANGED) return QueryResolution.retryResolution();
            SnapshotReadView built = buildReadView(projectId, descriptor);
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

    private Optional<SnapshotReadView> cachedView(CacheKey key, SnapshotDescriptor descriptor) {
        synchronized (cacheLock) {
            SnapshotReadView cached = queryCache.get(key);
            if (cached != null && cached.descriptor().equals(descriptor)) {
                cacheHits++;
                return Optional.of(cached);
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
            SnapshotReadView built
    ) throws IOException {
        long weight = estimateQueryViewWeight(projectId, descriptor);
        synchronized (cacheLock) {
            SnapshotReadView raced = queryCache.get(key);
            if (raced != null && raced.descriptor().equals(descriptor)) {
                cacheHits++;
                return QueryResolution.complete(Optional.of(raced));
            }
            removeProjectEntries(projectId, key);
            queryCache.put(key, built, weight);
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
                    queryCache.weight(), maxQueryCacheWeightBytes, queryCache.evictions());
        }
    }

    private SnapshotReadView buildReadView(UUID projectId, SnapshotDescriptor descriptor) throws IOException {
        Path snapshotFile = snapshotRepository.resolveSnapshotFile(projectId, descriptor.fileName());
        if (!Files.isRegularFile(snapshotFile)) {
            throw new IOException("active symbol snapshot file is missing: " + leafName(snapshotFile));
        }
        // One pass: the codec hashes what it decodes and returns nothing unless the SHA-256 is the pointer's.
        CodeKnowledgeSnapshot snapshot =
                codecFor(descriptor.formatVersion()).readVerified(snapshotFile, descriptor.sha256());
        recordFullSnapshotLoad();
        integrityService.verifyMetadata(snapshot, projectId, descriptor);
        SnapshotReadView view = SnapshotReadView.indexed(descriptor, snapshot);
        recordQueryViewBuild();
        return view;
    }

    private void recordFullSnapshotLoad() {
        synchronized (cacheLock) { fullSnapshotLoads++; }
    }

    private void recordQueryViewBuild() {
        synchronized (cacheLock) { queryViewBuilds++; }
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot, KnowledgeSnapshotCodecs.Selection selection)
            throws IOException {
        // Codec and exact size are chosen, and an oversized snapshot refused, before any I/O: no temporary
        // file, no partial file, no pointer change (A6).
        SnapshotCodec codec = selection.codec();
        long encodedBytes = selection.encodedBytes();
        Path temporarySnapshot = snapshotRepository.createTemporarySnapshot(snapshot.projectId());
        try {
            SnapshotCodec.SnapshotEncoding encoding = codec.write(temporarySnapshot, snapshot);
            if (Files.size(temporarySnapshot) != encodedBytes) {
                throw new IOException("snapshot encoding length differs from its computed size");
            }
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
        queryCache.removeIf(key -> key.projectId().equals(projectId) && !key.equals(except));
    }

    private ReentrantLock buildLock(UUID projectId) {
        return buildLocks[Math.floorMod(projectId.hashCode(), buildLocks.length)];
    }

    private SnapshotCodec codecFor(int version) throws IOException {
        return switch (version) {
            case ActiveSnapshotRepository.FORMAT_VERSION_V1,
                 ActiveSnapshotRepository.FORMAT_VERSION_V2,
                 ActiveSnapshotRepository.FORMAT_VERSION_V3 -> KnowledgeSnapshotCodecs.forVersion(version);
            default -> throw new IOException("unsupported active snapshot pointer version: " + version);
        };
    }

    private long estimateQueryViewWeight(UUID projectId, SnapshotDescriptor descriptor) throws IOException {
        long countWeight = 64L * 1024L;
        countWeight = saturatingAdd(countWeight, descriptor.symbolCount() * 1024L);
        countWeight = saturatingAdd(countWeight, descriptor.occurrenceCount() * 640L);
        countWeight = saturatingAdd(countWeight, descriptor.relationshipCount() * 1024L);
        Path snapshot = snapshotRepository.resolveSnapshotFile(projectId, descriptor.fileName());
        long persistedWeight = saturatingMultiply(Files.size(snapshot), QUERY_VIEW_PERSISTED_AMPLIFICATION);
        // AUD-PERF-11: the derived index of incoming edges, built later, lives as long as the cached view.
        return saturatingAdd(Math.max(countWeight, persistedWeight),
                SnapshotReadView.incomingRelationshipsWeightBytes(descriptor.relationshipCount()));
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
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

    private enum PointerState { MATCH, CHANGED, MISSING }

    private record QueryResolution(Optional<SnapshotReadView> view, boolean shouldRetry) {
        private static QueryResolution complete(Optional<SnapshotReadView> view) { return new QueryResolution(view, false); }
        private static QueryResolution retryResolution() { return new QueryResolution(Optional.empty(), true); }
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
