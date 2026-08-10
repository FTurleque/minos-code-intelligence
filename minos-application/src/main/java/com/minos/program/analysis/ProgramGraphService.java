package com.minos.program.analysis;

import com.minos.application.ProjectResolver;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Long-lived bounded query service for reconstructible M19 program graphs. */
public final class ProgramGraphService {

    public static final int DEFAULT_MAX_CACHE_ENTRIES = 16;
    public static final int DEFAULT_MAX_NODES = 10_000;
    public static final int DEFAULT_MAX_EDGES = 50_000;
    private static final int PUBLIC_MAX_NODES = 100_000;
    private static final int PUBLIC_MAX_EDGES = 500_000;
    private static final int BUILD_LOCK_STRIPES = 64;

    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final List<ProgramGraphProvider> providers;
    private final int maxCacheEntries;
    private final LinkedHashMap<CacheKey, ProgramGraph> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock[] buildLocks = buildLocks();
    private long cacheHits;
    private long cacheMisses;
    private long providerKeyNanos;
    private long analysisNanos;

    public ProgramGraphService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshotStore) {
        this(registry, snapshotStore, productionProviders());
    }

    public ProgramGraphService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshotStore,
                               List<ProgramGraphProvider> providers) {
        this(new ProjectResolver(registry), snapshotStore, providers, DEFAULT_MAX_CACHE_ENTRIES);
    }

    public ProgramGraphService(ProjectResolver projectResolver, CodeKnowledgeSnapshotStore snapshotStore,
                               List<ProgramGraphProvider> providers, int maxCacheEntries) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.providers = normalizeProviders(providers);
        if (maxCacheEntries < 1) throw new IllegalArgumentException("maxCacheEntries must be greater than zero");
        this.maxCacheEntries = maxCacheEntries;
    }

    public static List<ProgramGraphProvider> productionProviders() {
        return List.of(new RelationshipProgramGraphProvider(), new JavaSourceProgramGraphProvider());
    }

    public static List<ProgramGraphProvider> productionProviders(ProjectFingerprintSnapshotStore fingerprints) {
        return List.of(new RelationshipProgramGraphProvider(), new FingerprintConstrainedJavaProgramGraphProvider(fingerprints));
    }

    public Set<String> providerIds() {
        return providers.stream().map(ProgramGraphProvider::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public CacheStats cacheStats() {
        synchronized (cache) {
            return new CacheStats(cacheHits, cacheMisses, providerKeyNanos, analysisNanos, cache.size(), maxCacheEntries);
        }
    }

    public ProgramGraph getGraph(String projectIdentifier) throws IOException {
        return getGraph(projectIdentifier, DEFAULT_MAX_NODES, DEFAULT_MAX_EDGES);
    }

    public ProgramGraph getGraph(String projectIdentifier, int maxNodes, int maxEdges) throws IOException {
        requireBound(maxNodes, 1, PUBLIC_MAX_NODES, "maxNodes");
        requireBound(maxEdges, 1, PUBLIC_MAX_EDGES, "maxEdges");
        return graph(projectIdentifier, new ProgramGraphBudget(maxNodes, maxEdges));
    }

    ProgramGraph fullGraph(String projectIdentifier) throws IOException {
        return graph(projectIdentifier, new ProgramGraphBudget(PUBLIC_MAX_NODES, PUBLIC_MAX_EDGES));
    }

    private ProgramGraph graph(String projectIdentifier, ProgramGraphBudget budget) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active symbol snapshot: " + project.id()));
        long keyStarted = System.nanoTime();
        String providersKey = providerKey(project, snapshot);
        long keyElapsed = System.nanoTime() - keyStarted;
        CacheKey key = new CacheKey(
                project.id().toString(), snapshot.snapshotId(), providersKey, budget.maxNodes(), budget.maxEdges());

        synchronized (cache) {
            providerKeyNanos += keyElapsed;
            ProgramGraph cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
        }

        ReentrantLock buildLock = buildLock(project.id().toString());
        buildLock.lock();
        try {
            synchronized (cache) {
                ProgramGraph cached = cache.get(key);
                if (cached != null) {
                    cacheHits++;
                    return cached;
                }
            }

            long analysisStarted = System.nanoTime();
            List<ProgramGraph> fragments = new ArrayList<>();
            for (ProgramGraphProvider provider : providers) {
                fragments.add(provider.analyze(project, snapshot, budget));
            }
            ProgramGraph composed = withCapabilityLimitations(new ProgramGraphComposer().compose(
                    project.id().toString(), snapshot.snapshotId(), fragments, budget));
            long analysisElapsed = System.nanoTime() - analysisStarted;

            synchronized (cache) {
                cacheMisses++;
                analysisNanos += analysisElapsed;
                cache.entrySet().removeIf(entry -> entry.getKey().projectId().equals(project.id().toString())
                        && entry.getKey().maxNodes() == budget.maxNodes()
                        && entry.getKey().maxEdges() == budget.maxEdges()
                        && !entry.getKey().equals(key));
                cache.put(key, composed);
                while (cache.size() > maxCacheEntries) cache.remove(cache.keySet().iterator().next());
            }
            return composed;
        } finally {
            buildLock.unlock();
        }
    }

    private ReentrantLock buildLock(String projectId) {
        return buildLocks[Math.floorMod(projectId.hashCode(), buildLocks.length)];
    }

    private ProgramGraph withCapabilityLimitations(ProgramGraph graph) {
        Set<ProgramGraphCapability> capabilities = new LinkedHashSet<>(graph.capabilities());
        Set<String> limitations = new LinkedHashSet<>(graph.limitations());
        if (!capabilities.contains(ProgramGraphCapability.CALL_GRAPH)) limitations.add("CALL_GRAPH_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.CONTROL_FLOW)) limitations.add("CONTROL_FLOW_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.LOCAL_DATA_FLOW)) limitations.add("LOCAL_DATA_FLOW_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)) limitations.add("INTERPROCEDURAL_DATA_FLOW_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.SECURITY_TAINT)) limitations.add("SECURITY_ANNOTATIONS_UNAVAILABLE");
        return new ProgramGraph(graph.projectId(), graph.snapshotId(), capabilities, graph.nodes(), graph.edges(),
                limitations.stream().sorted().toList());
    }

    private String providerKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        List<String> keys = new ArrayList<>(providers.size());
        for (ProgramGraphProvider provider : providers) {
            String key = provider.cacheKey(project, snapshot);
            if (key == null || key.isBlank()) throw new IllegalStateException("program graph provider returned a blank cache key: " + provider.id());
            keys.add(provider.id() + "=" + key);
        }
        return keys.stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static List<ProgramGraphProvider> normalizeProviders(List<ProgramGraphProvider> providers) {
        List<ProgramGraphProvider> requested = new ArrayList<>(Objects.requireNonNull(providers, "providers"));
        if (requested.isEmpty()) throw new IllegalArgumentException("at least one program graph provider is required");
        Map<String, ProgramGraphProvider> configured = new LinkedHashMap<>();
        for (ProgramGraphProvider provider : requested) {
            ProgramGraphProvider nonNull = Objects.requireNonNull(provider, "providers must not contain null");
            ProgramGraphProvider previous = configured.putIfAbsent(nonNull.id(), nonNull);
            if (previous != null && previous.getClass() != nonNull.getClass()) {
                throw new IllegalArgumentException("conflicting program graph providers share id: " + nonNull.id());
            }
        }
        if (configured.containsKey(RelationshipProgramGraphProvider.PROVIDER_ID)
                && !configured.containsKey(JavaSourceProgramGraphProvider.PROVIDER_ID)) {
            configured.put(JavaSourceProgramGraphProvider.PROVIDER_ID, new JavaSourceProgramGraphProvider());
        }
        configured.putIfAbsent(FileProgramGraphProvider.PROVIDER_ID, new FileProgramGraphProvider());
        return List.copyOf(configured.values());
    }

    private static ReentrantLock[] buildLocks() {
        ReentrantLock[] locks = new ReentrantLock[BUILD_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }

    private static void requireBound(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
    }

    public record CacheStats(long hits, long misses, long providerKeyNanos, long analysisNanos, int entries, int maximumEntries) { }
    private record CacheKey(String projectId, String snapshotId, String providers, int maxNodes, int maxEdges) { }
}
