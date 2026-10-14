package com.minos.program.analysis;

import com.minos.application.resolution.ProjectResolver;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Long-lived bounded query service for reconstructible M19 program graphs. */
public final class ProgramGraphService {

    public static final int DEFAULT_MAX_CACHE_ENTRIES = 16;
    public static final long DEFAULT_MAX_CACHE_WEIGHT = 256L * 1024L * 1024L;
    public static final int DEFAULT_MAX_NODES = 10_000;
    public static final int DEFAULT_MAX_EDGES = 50_000;
    public static final int PUBLIC_MAX_NODES = 100_000;
    public static final int PUBLIC_MAX_EDGES = 500_000;
    private static final int BUILD_LOCK_STRIPES = 64;
    /**
     * How long a cache key obtained by hashing the project sources is reused before they are hashed again, when
     * the snapshot has no exact fingerprint (AUD-PERF-07).
     */
    public static final int SOURCE_STATE_REUSE_SECONDS = 5;
    /**
     * Declared on every graph whose Java contribution comes from the sources on disk without an exact snapshot
     * fingerprint: the graph is the disk as it was at most {@link #SOURCE_STATE_REUSE_SECONDS} seconds ago, not
     * the indexed state. Running {@code minos index} on a stable tree restores the exact fingerprint.
     */
    public static final String SOURCE_STATE_REUSE_LIMITATION =
            "PROGRAM_GRAPH_REFLECTS_DISK_AS_OF_AT_MOST_" + SOURCE_STATE_REUSE_SECONDS
                    + "_SECONDS_AGO_NOT_INDEXED_STATE";

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
    private final AtomicLong impactAdjacencyBuilds = new AtomicLong();

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

    /** How many impact adjacencies this service has built: one per graph it serves to impact analysis. */
    long impactAdjacencyBuilds() {
        return impactAdjacencyBuilds.get();
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
        return fullEntry(projectIdentifier).graph();
    }

    /**
     * The full graph with the traversal adjacency impact analysis needs, held by the same cache entry: the
     * adjacency is built once per cached graph, on the first impact analysis, and charged in the entry weight.
     */
    ImpactGraph impactGraph(String projectIdentifier) throws IOException {
        GraphEntry entry = fullEntry(projectIdentifier);
        return new ImpactGraph(entry.graph(), entry.adjacency());
    }

    private GraphEntry fullEntry(String projectIdentifier) throws IOException {
        return entry(projectIdentifier, new ProgramGraphBudget(PUBLIC_MAX_NODES, PUBLIC_MAX_EDGES));
    }

    private ProgramGraph graph(String projectIdentifier, ProgramGraphBudget budget) throws IOException {
        return entry(projectIdentifier, budget).graph();
    }

    private GraphEntry entry(String projectIdentifier, ProgramGraphBudget budget) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active symbol snapshot: " + project.id()));
        long keyStarted = System.nanoTime();
        ProviderKey providersKey = providerKey(project, snapshot);
        long keyElapsed = System.nanoTime() - keyStarted;
        CacheKey key = new CacheKey(project.id().toString(), snapshot.snapshotId(), providersKey.key(),
                budget.maxNodes(), budget.maxEdges());

        synchronized (cache) {
            providerKeyNanos += keyElapsed;
            WeightedGraph cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                return cached.entry();
            }
        }

        ReentrantLock buildLock = buildLock(project.id().toString());
        buildLock.lock();
        try {
            synchronized (cache) {
                WeightedGraph cached = cache.get(key);
                if (cached != null) {
                    cacheHits++;
                    return cached.entry();
                }
            }

            long analysisStarted = System.nanoTime();
            List<ProgramGraph> fragments = new ArrayList<>();
            for (ProgramGraphProvider provider : providers) {
                fragments.add(provider.analyze(project, snapshot, budget));
            }
            ProgramGraph composed = withCapabilityLimitations(new ProgramGraphComposer().compose(
                    project.id().toString(), snapshot.snapshotId(), fragments, budget),
                    providersKey.sourceStateReused());
            long analysisElapsed = System.nanoTime() - analysisStarted;
            GraphEntry entry = new GraphEntry(composed, impactAdjacencyBuilds);

            synchronized (cache) {
                cacheMisses++;
                analysisNanos += analysisElapsed;
                removeSupersededEntries(project.id().toString(), budget, key);
                long weight = saturatingAdd(graphWeight(composed), ImpactAdjacency.weight(composed));
                if (weight <= maxCacheWeight) {
                    WeightedGraph previous = cache.put(key, new WeightedGraph(entry, weight));
                    if (previous != null) cacheWeight -= previous.weightBytes();
                    cacheWeight = saturatingAdd(cacheWeight, weight);
                    evictCache();
                }
            }
            return entry;
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

    private ProgramGraph withCapabilityLimitations(ProgramGraph graph, boolean sourceStateReused) {
        Set<ProgramGraphCapability> capabilities = new LinkedHashSet<>(graph.capabilities());
        Set<String> limitations = new LinkedHashSet<>(graph.limitations());
        if (sourceStateReused) limitations.add(SOURCE_STATE_REUSE_LIMITATION);
        if (!capabilities.contains(ProgramGraphCapability.CALL_GRAPH)) limitations.add("CALL_GRAPH_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.CONTROL_FLOW)) limitations.add("CONTROL_FLOW_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.LOCAL_DATA_FLOW)) limitations.add("LOCAL_DATA_FLOW_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)) limitations.add("INTERPROCEDURAL_DATA_FLOW_UNAVAILABLE");
        if (!capabilities.contains(ProgramGraphCapability.SECURITY_TAINT)) limitations.add("SECURITY_ANNOTATIONS_UNAVAILABLE");
        return new ProgramGraph(graph.projectId(), graph.snapshotId(), capabilities, graph.nodes(), graph.edges(),
                limitations.stream().sorted().toList());
    }

    /**
     * The key is content-derived, so a key reused without re-reading the sources and the same key freshly
     * computed name the same graph: the declaration travels with the cached graph.
     */
    private ProviderKey providerKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        List<String> keys = new ArrayList<>(providers.size());
        boolean sourceStateReused = false;
        for (ProgramGraphProvider provider : providers) {
            ProgramGraphProvider.CacheKeyResolution resolution = provider.resolveCacheKey(project, snapshot);
            String key = resolution.key();
            if (key == null || key.isBlank()) throw new IllegalStateException("program graph provider returned a blank cache key: " + provider.id());
            keys.add(provider.id() + "=" + key);
            sourceStateReused |= resolution.sourceStateReused();
        }
        return new ProviderKey(
                keys.stream().sorted().reduce((left, right) -> left + "," + right).orElse(""), sourceStateReused);
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
    private record WeightedGraph(GraphEntry entry, long weightBytes) { }

    /** A program graph with its impact adjacency, as one cache entry holds them. */
    record ImpactGraph(ProgramGraph graph, ImpactAdjacency adjacency) { }

    /**
     * One composed graph and its impact adjacency, built on first demand and then kept as long as the graph:
     * concurrent first readers wait for the single build (AUD-PERF-11).
     */
    private static final class GraphEntry {
        private final ProgramGraph graph;
        private final AtomicLong builds;
        private final AtomicReference<ImpactAdjacency> adjacency = new AtomicReference<>();
        private final Object buildMonitor = new Object();

        private GraphEntry(ProgramGraph graph, AtomicLong builds) {
            this.graph = graph;
            this.builds = builds;
        }

        ProgramGraph graph() {
            return graph;
        }

        ImpactAdjacency adjacency() {
            ImpactAdjacency built = adjacency.get();
            if (built != null) return built;
            synchronized (buildMonitor) {
                built = adjacency.get();
                if (built == null) {
                    builds.incrementAndGet();
                    built = ImpactAdjacency.of(graph);
                    adjacency.set(built);
                }
                return built;
            }
        }
    }

    /**
     * Program-graph adjacency in the direction impact propagates: a call is followed from callee to caller, every
     * other edge but control flow from source to target. Each list keeps the edges in identifier order.
     */
    static final class ImpactAdjacency {
        private static final long FIXED_WEIGHT = 1_024L;
        private static final long WEIGHT_PER_NODE = 64L;
        private static final long WEIGHT_PER_EDGE = 96L;

        private final Map<String, ProgramGraphNode> nodesById;
        private final Map<String, List<TraversalEdge>> next;

        private ImpactAdjacency(Map<String, ProgramGraphNode> nodesById, Map<String, List<TraversalEdge>> next) {
            this.nodesById = nodesById;
            this.next = next;
        }

        static ImpactAdjacency of(ProgramGraph graph) {
            Map<String, ProgramGraphNode> nodes = new HashMap<>();
            graph.nodes().forEach(node -> nodes.put(node.id(), node));
            Map<String, List<TraversalEdge>> result = new HashMap<>();
            List<ProgramGraphEdge> ordered = graph.edges().stream().sorted(Comparator.comparing(ProgramGraphEdge::id)).toList();
            for (ProgramGraphEdge edge : ordered) {
                if (edge.kind() == ProgramEdgeKind.CONTROL_FLOW) continue;
                if (edge.kind() == ProgramEdgeKind.CALL) {
                    result.computeIfAbsent(edge.targetNodeId(), ignored -> new ArrayList<>())
                            .add(new TraversalEdge(edge, edge.sourceNodeId()));
                } else {
                    result.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>())
                            .add(new TraversalEdge(edge, edge.targetNodeId()));
                }
            }
            Map<String, List<TraversalEdge>> frozen = new HashMap<>();
            result.forEach((nodeId, edges) -> frozen.put(nodeId, List.copyOf(edges)));
            return new ImpactAdjacency(Map.copyOf(nodes), Map.copyOf(frozen));
        }

        /** Upper bound of the heap the adjacency of this graph retains: its own slots, never the graph facts. */
        static long weight(ProgramGraph graph) {
            long weight = saturatingAdd(FIXED_WEIGHT, (long) graph.nodes().size() * WEIGHT_PER_NODE);
            return saturatingAdd(weight, (long) graph.edges().size() * WEIGHT_PER_EDGE);
        }

        ProgramGraphNode node(String nodeId) {
            return nodesById.get(nodeId);
        }

        List<TraversalEdge> next(String nodeId) {
            return next.getOrDefault(nodeId, List.of());
        }
    }

    record TraversalEdge(ProgramGraphEdge edge, String nextNodeId) { }
    private record ProviderKey(String key, boolean sourceStateReused) { }
}
