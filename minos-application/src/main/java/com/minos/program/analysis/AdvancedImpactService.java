package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ProjectImpactQuery;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Impact v2: preserves the baseline impact analysis and adds only program-graph paths not already reported. */
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
        // AUD-PERF-11: the sorted adjacency is the one of the graph's cache entry, built once for it.
        ProgramGraphService.ImpactGraph impactGraph = programGraphs.impactGraph(projectIdentifier);
        ProgramGraph graph = impactGraph.graph();
        ProgramGraphService.ImpactAdjacency adjacency = impactGraph.adjacency();
        String rootNodeId = "symbol:" + request.symbolId();

        Set<String> baselineIds = new HashSet<>();
        baselineIds.add(request.symbolId());
        baseline.impacts().forEach(item -> baselineIds.add(item.symbol().id()));
        baseline.potentiallyImpactedTests().forEach(item -> baselineIds.add(item.symbol().id()));

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
                if (!adjacency.next(state.nodeId()).isEmpty()) depthReached = true;
                continue;
            }
            for (ProgramGraphService.TraversalEdge traversal : adjacency.next(state.nodeId())) {
                ProgramGraphEdge edge = traversal.edge();
                String next = traversal.nextNodeId();
                if (!visited.add(next)) {
                    limitations.add("CYCLE_OBSERVED");
                    continue;
                }
                double confidence = Math.min(state.confidence(), edge.confidence() == null ? 1.0 : edge.confidence());
                InformationNature nature = mergeNature(state.nature(), edge.nature());
                List<String> path = append(state.edgeIds(), edge.id());
                ProgramGraphNode node = adjacency.node(next);
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
