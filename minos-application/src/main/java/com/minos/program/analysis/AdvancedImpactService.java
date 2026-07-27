package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ProjectImpactQuery;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** M19 Impact v2: preserves M8 as baseline and adds only program-graph paths not already reported. */
public final class AdvancedImpactService {

    private final ProjectImpactQuery baselineImpact;
    private final ProgramGraphService programGraphs;

    public AdvancedImpactService(ProjectImpactQuery baselineImpact, ProgramGraphService programGraphs) {
        this.baselineImpact = Objects.requireNonNull(baselineImpact, "baselineImpact");
        this.programGraphs = Objects.requireNonNull(programGraphs, "programGraphs");
    }

    public AdvancedImpactReport analyze(String projectIdentifier, ImpactAnalysisRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        ImpactAnalysisReport baseline = baselineImpact.analyzeImpact(projectIdentifier, request);
        ProgramGraph graph = programGraphs.fullGraph(projectIdentifier);
        String rootNodeId = "symbol:" + request.symbolId();

        Set<String> baselineIds = new HashSet<>();
        baselineIds.add(request.symbolId());
        baseline.impacts().forEach(item -> baselineIds.add(item.symbol().id()));
        baseline.potentiallyImpactedTests().forEach(item -> baselineIds.add(item.symbol().id()));

        Map<String, ProgramGraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, List<TraversalEdge>> adjacency = adjacency(graph);
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(new State(rootNodeId, List.of(), 0, 1.0, InformationNature.FACTUAL));
        Set<String> visited = new HashSet<>();
        visited.add(rootNodeId);
        List<AdvancedImpactItem> added = new ArrayList<>();
        Set<String> limitations = new LinkedHashSet<>(graph.limitations());

        boolean depthReached = false;
        while (!queue.isEmpty() && added.size() < request.maxResults()) {
            State state = queue.removeFirst();
            if (state.depth() >= request.maxDepth()) {
                if (!adjacency.getOrDefault(state.nodeId(), List.of()).isEmpty()) depthReached = true;
                continue;
            }
            for (TraversalEdge traversal : adjacency.getOrDefault(state.nodeId(), List.of())) {
                ProgramGraphEdge edge = traversal.edge();
                String next = traversal.nextNodeId();
                if (!visited.add(next)) {
                    limitations.add("CYCLE_OBSERVED");
                    continue;
                }
                double confidence = Math.min(state.confidence(), edge.confidence() == null ? 1.0 : edge.confidence());
                InformationNature nature = mergeNature(state.nature(), edge.nature());
                List<String> path = append(state.edgeIds(), edge.id());
                ProgramGraphNode node = nodes.get(next);
                if (node != null && node.symbolId() != null && !baselineIds.contains(node.symbolId())) {
                    added.add(new AdvancedImpactItem(
                            node.symbolId(), node.label(), state.depth() + 1, confidence, nature, path));
                    baselineIds.add(node.symbolId());
                    if (added.size() >= request.maxResults()) break;
                }
                queue.addLast(new State(next, path, state.depth() + 1, confidence, nature));
            }
        }
        if (depthReached) limitations.add("MAX_DEPTH_REACHED");
        if (!queue.isEmpty()) limitations.add("MAX_RESULTS_REACHED");
        limitations.add("DYNAMIC_DISPATCH_NOT_PROVEN");
        limitations.add("RUNTIME_CONFIGURATION_NOT_PROVEN");

        return new AdvancedImpactReport(
                graph.projectId(), graph.snapshotId(), baseline, List.copyOf(added),
                limitations.stream().sorted().toList());
    }

    private static Map<String, List<TraversalEdge>> adjacency(ProgramGraph graph) {
        Map<String, List<TraversalEdge>> result = new LinkedHashMap<>();
        for (ProgramGraphEdge edge : graph.edges().stream().sorted(Comparator.comparing(ProgramGraphEdge::id)).toList()) {
            if (edge.kind() == ProgramEdgeKind.CONTROL_FLOW) continue;
            if (edge.kind() == ProgramEdgeKind.CALL) {
                result.computeIfAbsent(edge.targetNodeId(), ignored -> new ArrayList<>())
                        .add(new TraversalEdge(edge, edge.sourceNodeId()));
            } else {
                result.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>())
                        .add(new TraversalEdge(edge, edge.targetNodeId()));
            }
        }
        return result;
    }

    private static InformationNature mergeNature(InformationNature left, InformationNature right) {
        if (left == InformationNature.HEURISTIC || right == InformationNature.HEURISTIC) return InformationNature.HEURISTIC;
        if (left == InformationNature.DERIVED || right == InformationNature.DERIVED) return InformationNature.DERIVED;
        return InformationNature.FACTUAL;
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(value);
        return List.copyOf(result);
    }

    private record TraversalEdge(ProgramGraphEdge edge, String nextNodeId) {
    }

    private record State(
            String nodeId,
            List<String> edgeIds,
            int depth,
            double confidence,
            InformationNature nature
    ) {
    }

    public record AdvancedImpactItem(
            String symbolId,
            String label,
            int depth,
            double confidence,
            InformationNature nature,
            List<String> programEdgePath
    ) {
        public AdvancedImpactItem {
            programEdgePath = List.copyOf(programEdgePath);
        }
    }

    public record AdvancedImpactReport(
            String projectId,
            String snapshotId,
            ImpactAnalysisReport baseline,
            List<AdvancedImpactItem> advancedAdded,
            List<String> limitations
    ) {
        public AdvancedImpactReport {
            Objects.requireNonNull(baseline, "baseline");
            advancedAdded = List.copyOf(advancedAdded);
            limitations = List.copyOf(limitations);
        }

        public int baselineCount() {
            return baseline.impacts().size();
        }

        public int advancedAddedCount() {
            return advancedAdded.size();
        }

        public int totalCount() {
            return baselineCount() + advancedAddedCount();
        }
    }
}
