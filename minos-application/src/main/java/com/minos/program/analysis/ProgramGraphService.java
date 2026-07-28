package com.minos.program.analysis;

import com.minos.application.ProjectResolver;
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

    public ProgramGraphService(LocalProjectRegistry registry, FileSymbolSnapshotStore snapshotStore) {
        this(registry, snapshotStore, List.of(
                new RelationshipProgramGraphProvider(),
                new JavaSourceProgramGraphProvider()));
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
        List<ProgramGraphProvider> configured = new ArrayList<>(Objects.requireNonNull(providers, "providers"));
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("at least one program graph provider is required");
        }
        if (configured.stream().noneMatch(provider -> FileProgramGraphProvider.PROVIDER_ID.equals(provider.id()))) {
            configured.add(new FileProgramGraphProvider());
        }
        this.providers = List.copyOf(configured);
        if (maxCacheEntries < 1) {
            throw new IllegalArgumentException("maxCacheEntries must be greater than zero");
        }
        this.maxCacheEntries = maxCacheEntries;
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
        Set<String> nodeIds = nodes.stream().map(ProgramGraphNode::id).collect(java.util.stream.Collectors.toSet());
        List<ProgramGraphEdge> edges = full.edges().stream()
                .filter(edge -> nodeIds.contains(edge.sourceNodeId()) && nodeIds.contains(edge.targetNodeId()))
                .limit(maxEdges)
                .toList();
        Set<String> limitations = new LinkedHashSet<>(full.limitations());
        if (full.nodes().size() > nodes.size()) limitations.add("PROGRAM_GRAPH_NODE_LIMIT_REACHED");
        if (full.edges().size() > edges.size()) limitations.add("PROGRAM_GRAPH_EDGE_LIMIT_REACHED");
        return new ProgramGraph(full.projectId(), full.snapshotId(), full.capabilities(), nodes, edges,
                limitations.stream().sorted().toList());
    }

    ProgramGraph fullGraph(String projectIdentifier) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active symbol snapshot: " + project.id()));
        CacheKey key = new CacheKey(project.id().toString(), snapshot.snapshotId(), providerKey(project, snapshot));
        synchronized (cache) {
            ProgramGraph cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            List<ProgramGraph> fragments = new ArrayList<>();
            for (ProgramGraphProvider provider : providers) {
                fragments.add(provider.analyze(project, snapshot));
            }
            ProgramGraph composed = withCapabilityLimitations(
                    new ProgramGraphComposer().compose(project.id().toString(), snapshot.snapshotId(), fragments));
            cache.entrySet().removeIf(entry -> entry.getKey().projectId().equals(project.id().toString()) && !entry.getKey().equals(key));
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
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("program graph provider returned a blank cache key: " + provider.id());
            }
            keys.add(provider.id() + "=" + key);
        }
        return keys.stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static void requireBound(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private record CacheKey(String projectId, String snapshotId, String providers) {
    }
}
