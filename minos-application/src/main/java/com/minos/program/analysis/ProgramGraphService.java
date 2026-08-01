package com.minos.program.analysis;

import com.minos.application.ProjectResolver;
import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Long-lived bounded query service for reconstructible M19 program graphs. */
public final class ProgramGraphService {

    public static final int DEFAULT_MAX_CACHE_ENTRIES = 16;
    public static final int DEFAULT_MAX_NODES = 10_000;
    public static final int DEFAULT_MAX_EDGES = 50_000;

    private final ProjectResolver projectResolver;
    private final FileSymbolSnapshotStore snapshotStore;
    private final List<ProgramGraphProvider> providers;
    private final int maxCacheEntries;
    private final LinkedHashMap<CacheKey, ProgramGraph> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheHits;
    private long cacheMisses;
    private long providerKeyNanos;
    private long analysisNanos;

    public ProgramGraphService(LocalProjectRegistry registry, FileSymbolSnapshotStore snapshotStore) {
        this(registry, snapshotStore, productionProviders());
    }

    public ProgramGraphService(
            LocalProjectRegistry registry,
            FileSymbolSnapshotStore snapshotStore,
            List<ProgramGraphProvider> providers
    ) {
        this(new ProjectResolver(registry), snapshotStore, providers, DEFAULT_MAX_CACHE_ENTRIES);
    }

    public ProgramGraphService(
            ProjectResolver projectResolver,
            FileSymbolSnapshotStore snapshotStore,
            List<ProgramGraphProvider> providers,
            int maxCacheEntries
    ) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.providers = normalizeProviders(providers);
        if (maxCacheEntries < 1) {
            throw new IllegalArgumentException("maxCacheEntries must be greater than zero");
        }
        this.maxCacheEntries = maxCacheEntries;
    }

    /** Authoritative local composition when no persisted fingerprint store is available. */
    public static List<ProgramGraphProvider> productionProviders() {
        return List.of(new RelationshipProgramGraphProvider(), new JavaSourceProgramGraphProvider());
    }

    /**
     * Authoritative production composition using the immutable fingerprint associated with each
     * promoted snapshot, avoiding full Java source re-hashing on warm cache hits.
     */
    public static List<ProgramGraphProvider> productionProviders(
            FileProjectFingerprintSnapshotStore fingerprints
    ) {
        return List.of(
                new RelationshipProgramGraphProvider(),
                new FingerprintConstrainedJavaProgramGraphProvider(fingerprints));
    }

    /** Provider identifiers exposed for composition-root and surface contract tests. */
    public Set<String> providerIds() {
        return providers.stream()
                .map(ProgramGraphProvider::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Bounded cache measurements used by performance qualification and regression tests. */
    public CacheStats cacheStats() {
        synchronized (cache) {
            return new CacheStats(
                    cacheHits,
                    cacheMisses,
                    providerKeyNanos,
                    analysisNanos,
                    cache.size(),
                    maxCacheEntries);
        }
    }

    public ProgramGraph getGraph(String projectIdentifier) throws IOException {
        return getGraph(projectIdentifier, DEFAULT_MAX_NODES, DEFAULT_MAX_EDGES);
    }

    public ProgramGraph getGraph(String projectIdentifier, int maxNodes, int maxEdges) throws IOException {
        requireBound(maxNodes, 1, 100_000, "maxNodes");
        requireBound(maxEdges, 1, 500_000, "maxEdges");
        ProgramGraph full = fullGraph(projectIdentifier);
        if (full.nodes().size() <= maxNodes && full.edges().size() <= maxEdges) {
            return full;
        }

        List<ProgramGraphNode> nodes = full.nodes().stream().limit(maxNodes).toList();
        Set<String> nodeIds = nodes.stream().map(ProgramGraphNode::id)
                .collect(java.util.stream.Collectors.toSet());
        List<ProgramGraphEdge> edges = full.edges().stream()
                .filter(edge -> nodeIds.contains(edge.sourceNodeId()) && nodeIds.contains(edge.targetNodeId()))
                .limit(maxEdges)
                .toList();
        Set<String> limitations = new LinkedHashSet<>(full.limitations());
        if (full.nodes().size() > nodes.size()) {
            limitations.add("PROGRAM_GRAPH_NODE_LIMIT_REACHED");
        }
        if (full.edges().size() > edges.size()) {
            limitations.add("PROGRAM_GRAPH_EDGE_LIMIT_REACHED");
        }
        return new ProgramGraph(
                full.projectId(), full.snapshotId(), full.capabilities(), nodes, edges,
                limitations.stream().sorted().toList());
    }

    ProgramGraph fullGraph(String projectIdentifier) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active symbol snapshot: " + project.id()));
        long keyStarted = System.nanoTime();
        String providersKey = providerKey(project, snapshot);
        long keyElapsed = System.nanoTime() - keyStarted;
        CacheKey key = new CacheKey(project.id().toString(), snapshot.snapshotId(), providersKey);
        synchronized (cache) {
            providerKeyNanos += keyElapsed;
            ProgramGraph cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
            cacheMisses++;
            long analysisStarted = System.nanoTime();
            List<ProgramGraph> fragments = new ArrayList<>();
            for (ProgramGraphProvider provider : providers) {
                fragments.add(provider.analyze(project, snapshot));
            }
            ProgramGraph composed = withCapabilityLimitations(
                    new ProgramGraphComposer().compose(
                            project.id().toString(), snapshot.snapshotId(), fragments));
            analysisNanos += System.nanoTime() - analysisStarted;
            cache.entrySet().removeIf(entry ->
                    entry.getKey().projectId().equals(project.id().toString())
                            && !entry.getKey().equals(key));
            cache.put(key, composed);
            while (cache.size() > maxCacheEntries) {
                cache.remove(cache.keySet().iterator().next());
            }
            return composed;
        }
    }

    private ProgramGraph withCapabilityLimitations(ProgramGraph graph) {
        Set<ProgramGraphCapability> capabilities = new LinkedHashSet<>(graph.capabilities());
        Set<String> limitations = new LinkedHashSet<>(graph.limitations());
        if (!capabilities.contains(ProgramGraphCapability.CALL_GRAPH)) {
            limitations.add("CALL_GRAPH_UNAVAILABLE");
        }
        if (!capabilities.contains(ProgramGraphCapability.CONTROL_FLOW)) {
            limitations.add("CONTROL_FLOW_UNAVAILABLE");
        }
        if (!capabilities.contains(ProgramGraphCapability.LOCAL_DATA_FLOW)) {
            limitations.add("LOCAL_DATA_FLOW_UNAVAILABLE");
        }
        if (!capabilities.contains(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)) {
            limitations.add("INTERPROCEDURAL_DATA_FLOW_UNAVAILABLE");
        }
        if (!capabilities.contains(ProgramGraphCapability.SECURITY_TAINT)) {
            limitations.add("SECURITY_ANNOTATIONS_UNAVAILABLE");
        }
        return new ProgramGraph(
                graph.projectId(), graph.snapshotId(), capabilities, graph.nodes(), graph.edges(),
                limitations.stream().sorted().toList());
    }

    private String providerKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        List<String> keys = new ArrayList<>(providers.size());
        for (ProgramGraphProvider provider : providers) {
            String key = provider.cacheKey(project, snapshot);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                        "program graph provider returned a blank cache key: " + provider.id());
            }
            keys.add(provider.id() + "=" + key);
        }
        return keys.stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static List<ProgramGraphProvider> normalizeProviders(List<ProgramGraphProvider> providers) {
        List<ProgramGraphProvider> requested = new ArrayList<>(
                Objects.requireNonNull(providers, "providers"));
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("at least one program graph provider is required");
        }

        Map<String, ProgramGraphProvider> configured = new LinkedHashMap<>();
        for (ProgramGraphProvider provider : requested) {
            ProgramGraphProvider nonNull = Objects.requireNonNull(
                    provider, "providers must not contain null");
            ProgramGraphProvider previous = configured.putIfAbsent(nonNull.id(), nonNull);
            if (previous != null && previous.getClass() != nonNull.getClass()) {
                throw new IllegalArgumentException(
                        "conflicting program graph providers share id: " + nonNull.id());
            }
        }

        if (configured.containsKey(RelationshipProgramGraphProvider.PROVIDER_ID)
                && !configured.containsKey(JavaSourceProgramGraphProvider.PROVIDER_ID)) {
            configured.put(
                    JavaSourceProgramGraphProvider.PROVIDER_ID,
                    new JavaSourceProgramGraphProvider());
        }
        configured.putIfAbsent(FileProgramGraphProvider.PROVIDER_ID, new FileProgramGraphProvider());
        return List.copyOf(configured.values());
    }

    private static void requireBound(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    public record CacheStats(
            long hits,
            long misses,
            long providerKeyNanos,
            long analysisNanos,
            int entries,
            int maximumEntries
    ) {
    }

    private record CacheKey(String projectId, String snapshotId, String providers) {
    }
}
