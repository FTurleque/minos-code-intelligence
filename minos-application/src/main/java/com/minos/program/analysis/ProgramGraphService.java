package com.minos.program.analysis;

import com.minos.application.ProjectResolver;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
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
    public static final long DEFAULT_MAX_CACHE_WEIGHT = 256L * 1024L * 1024L;
    public static final int DEFAULT_MAX_NODES = 10_000;
    public static final int DEFAULT_MAX_EDGES = 50_000;
    private static final int PUBLIC_MAX_NODES = 100_000;
    private static final int PUBLIC_MAX_EDGES = 500_000;
    private static final int BUILD_LOCK_STRIPES = 64;

    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final List<ProgramGraphProvider> providers;
    private final int maxCacheEntries;
    private final long maxCacheWeight;
    private final LinkedHashMap<CacheKey, WeightedGraph> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock[] buildLocks = buildLocks();
    private long cacheWeight;
    private long cacheHits;
    private long cacheMisses;
    private long providerKeyNanos;
    private long analysisNanos;

    public ProgramGraphService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshotStore) {
        this(registry, snapshotStore, productionProviders());
    }

    public ProgramGraphService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshotStore,
                               List<ProgramGraphProvider> providers) {
        this(new ProjectResolver(registry), snapshotStore, providers, DEFAULT_MAX_CACHE_ENTRIES, DEFAULT_MAX_CACHE_WEIGHT);
    }

    public ProgramGraphService(ProjectResolver projectResolver, CodeKnowledgeSnapshotStore snapshotStore,
                               List<ProgramGraphProvider> providers, int maxCacheEntries) {
        this(projectResolver, snapshotStore, providers, maxCacheEntries, DEFAULT_MAX_CACHE_WEIGHT);
    }

    ProgramGraphService(ProjectResolver projectResolver, CodeKnowledgeSnapshotStore snapshotStore,
                        List<ProgramGraphProvider> providers, int maxCacheEntries, long maxCacheWeight) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.providers = normalizeProviders(providers);
        if (maxCacheEntries < 1) throw new IllegalArgumentException("maxCacheEntries must be greater than zero");
        if (maxCacheWeight < 1L) throw new IllegalArgumentException("maxCacheWeight must be greater than zero");
        this.maxCacheEntries = maxCacheEntries;
        this.maxCacheWeight = maxCacheWeight;
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
            return new CacheStats(cacheHits, cacheMisses, providerKeyNanos, analysisNanos,
                    cache.size(), maxCacheEntries, cacheWeight, maxCacheWeight);
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
            WeightedGraph cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                return cached.graph();
            }
        }

        ReentrantLock buildLock = buildLock(project.id().toString());
        buildLock.lock();
        try {
            synchronized (cache) {
                WeightedGraph cached = cache.get(key);
                if (cached != null) {
                    cacheHits++;
                    return cached.graph();
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
                removeSupersededEntries(project.id().toString(), budget, key);
                long weight = graphWeight(composed);
                if (weight <= maxCacheWeight) {
                    WeightedGraph previous = cache.put(key, new WeightedGraph(composed, weight));
                    if (previous != null) cacheWeight -= previous.weightBytes();
                    cacheWeight = saturatingAdd(cacheWeight, weight);
                    evictCache();
                }
            }
            return composed;
        } finally {
            buildLock.unlock();
        }
    }

    private void removeSupersededEntries(String projectId, ProgramGraphBudget budget, CacheKey currentKey) {
        Iterator<Map.Entry<CacheKey, WeightedGraph>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<CacheKey, WeightedGraph> entry = entries.next();
            CacheKey candidate = entry.getKey();
            if (candidate.projectId().equals(projectId)
                    && candidate.maxNodes() == budget.maxNodes()
                    && candidate.maxEdges() == budget.maxEdges()
                    && !candidate.equals(currentKey)) {
                cacheWeight -= entry.getValue().weightBytes();
                entries.remove();
            }
        }
    }

    private void evictCache() {
        Iterator<Map.Entry<CacheKey, WeightedGraph>> entries = cache.entrySet().iterator();
        while ((cache.size() > maxCacheEntries || cacheWeight > maxCacheWeight) && entries.hasNext()) {
            Map.Entry<CacheKey, WeightedGraph> eldest = entries.next();
            cacheWeight -= eldest.getValue().weightBytes();
            entries.remove();
        }
        if (cacheWeight < 0L) cacheWeight = 0L;
    }

    /** Conservative retained-heap estimate rather than a structural element count. */
    private static long graphWeight(ProgramGraph graph) {
        long weight = 2_048L;
        weight = saturatingAdd(weight, stringWeight(graph.projectId()));
        weight = saturatingAdd(weight, stringWeight(graph.snapshotId()));
        for (ProgramGraphNode node : graph.nodes()) {
            weight = saturatingAdd(weight, 256L);
            weight = saturatingAdd(weight, stringWeight(node.id()));
            weight = saturatingAdd(weight, stringWeight(node.projectId()));
            weight = saturatingAdd(weight, stringWeight(node.symbolId()));
            weight = saturatingAdd(weight, stringWeight(node.label()));
            weight = saturatingAdd(weight, locationWeight(node.location()));
            weight = saturatingAdd(weight, originWeight(node.origin()));
            for (Evidence evidence : node.evidence()) weight = saturatingAdd(weight, evidenceWeight(evidence));
        }
        for (ProgramGraphEdge edge : graph.edges()) {
            weight = saturatingAdd(weight, 224L);
            weight = saturatingAdd(weight, stringWeight(edge.id()));
            weight = saturatingAdd(weight, stringWeight(edge.projectId()));
            weight = saturatingAdd(weight, stringWeight(edge.sourceNodeId()));
            weight = saturatingAdd(weight, stringWeight(edge.targetNodeId()));
            weight = saturatingAdd(weight, originWeight(edge.origin()));
            for (Evidence evidence : edge.evidence()) weight = saturatingAdd(weight, evidenceWeight(evidence));
        }
        for (String limitation : graph.limitations()) weight = saturatingAdd(weight, stringWeight(limitation));
        return weight;
    }

    private static long evidenceWeight(Evidence evidence) {
        long weight = 192L;
        weight = saturatingAdd(weight, stringWeight(evidence.description()));
        if (evidence.source() != null) weight = saturatingAdd(weight, stringWeight(evidence.source().id()));
        if (evidence.target() != null) weight = saturatingAdd(weight, stringWeight(evidence.target().id()));
        return saturatingAdd(weight, locationWeight(evidence.location()));
    }

    private static long originWeight(Origin origin) {
        if (origin == null) return 0L;
        long weight = 160L;
        weight = saturatingAdd(weight, stringWeight(origin.providerId()));
        weight = saturatingAdd(weight, stringWeight(origin.providerType()));
        weight = saturatingAdd(weight, stringWeight(origin.providerVersion()));
        weight = saturatingAdd(weight, stringWeight(origin.indexRunId()));
        return weight;
    }

    private static long locationWeight(SymbolLocation location) {
        return location == null ? 0L : saturatingAdd(96L, stringWeight(location.fileId()));
    }

    private static long stringWeight(String value) {
        return value == null ? 0L : saturatingAdd(40L, (long) value.length() * Character.BYTES);
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
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

    public record CacheStats(long hits, long misses, long providerKeyNanos, long analysisNanos,
                             int entries, int maximumEntries, long weight, long maximumWeight) { }
    private record CacheKey(String projectId, String snapshotId, String providers, int maxNodes, int maxEdges) { }
    private record WeightedGraph(ProgramGraph graph, long weightBytes) { }
}
