package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;

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

/** Bounded source-to-sink path analysis. Findings are observations, never exhaustive vulnerability claims. */
public final class SecurityAnalysisService {

    private static final Set<ProgramEdgeKind> FLOW_KINDS = Set.of(
            ProgramEdgeKind.DEF_USE,
            ProgramEdgeKind.DATA_FLOW,
            ProgramEdgeKind.ARGUMENT_FLOW,
            ProgramEdgeKind.RETURN_FLOW,
            ProgramEdgeKind.TAINT_FLOW,
            ProgramEdgeKind.CALL);

    private final ProgramGraphService programGraphs;

    public SecurityAnalysisService(ProgramGraphService programGraphs) {
        this.programGraphs = Objects.requireNonNull(programGraphs, "programGraphs");
    }

    public SecurityReport analyze(String projectIdentifier, SecurityRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        ProgramGraph graph = programGraphs.fullGraph(projectIdentifier);
        Map<String, ProgramGraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, List<ProgramGraphEdge>> outgoing = outgoing(graph);
        List<ProgramGraphNode> sources = graph.nodes().stream()
                .filter(node -> node.kind() == ProgramNodeKind.SOURCE)
                .filter(node -> request.sourceNodeId() == null || request.sourceNodeId().equals(node.id()))
                .sorted(Comparator.comparing(ProgramGraphNode::id))
                .toList();
        Set<String> sinkIds = graph.nodes().stream()
                .filter(node -> node.kind() == ProgramNodeKind.SINK)
                .map(ProgramGraphNode::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> sanitizerIds = graph.nodes().stream()
                .filter(node -> node.kind() == ProgramNodeKind.SANITIZER)
                .map(ProgramGraphNode::id)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> limitations = new LinkedHashSet<>(graph.limitations());
        limitations.add("PATH_SEARCH_BOUNDED");
        limitations.add("ABSENCE_OF_PATH_IS_NOT_PROOF_OF_SAFETY");
        if (sources.isEmpty() || sinkIds.isEmpty()) {
            limitations.add("SECURITY_SOURCE_OR_SINK_ANNOTATIONS_UNAVAILABLE");
            return new SecurityReport(graph.projectId(), graph.snapshotId(), request, List.of(),
                    limitations.stream().sorted().toList());
        }

        List<SecurityPath> findings = new ArrayList<>();
        for (ProgramGraphNode source : sources) {
            if (findings.size() >= request.maxResults()) break;
            traverseSource(source, nodes, outgoing, sinkIds, sanitizerIds, request, findings, limitations);
        }
        return new SecurityReport(graph.projectId(), graph.snapshotId(), request, List.copyOf(findings),
                limitations.stream().sorted().toList());
    }

    private static void traverseSource(
            ProgramGraphNode source,
            Map<String, ProgramGraphNode> nodes,
            Map<String, List<ProgramGraphEdge>> outgoing,
            Set<String> sinkIds,
            Set<String> sanitizerIds,
            SecurityRequest request,
            List<SecurityPath> findings,
            Set<String> limitations
    ) {
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(new State(source.id(), List.of(source.id()), List.of(), List.of(), 0, 1.0, source.nature()));
        Set<String> seenStates = new HashSet<>();
        while (!queue.isEmpty() && findings.size() < request.maxResults()) {
            State state = queue.removeFirst();
            if (state.depth() >= request.maxDepth()) {
                if (!outgoing.getOrDefault(state.nodeId(), List.of()).isEmpty()) limitations.add("MAX_DEPTH_REACHED");
                continue;
            }
            for (ProgramGraphEdge edge : outgoing.getOrDefault(state.nodeId(), List.of())) {
                String next = edge.targetNodeId();
                boolean cycle = state.nodeIds().contains(next);
                if (cycle) {
                    limitations.add("CYCLE_OBSERVED");
                    continue;
                }
                List<String> nodesPath = append(state.nodeIds(), next);
                List<String> edgePath = append(state.edgeIds(), edge.id());
                List<String> sanitizers = sanitizerIds.contains(next) ? append(state.sanitizerNodeIds(), next) : state.sanitizerNodeIds();
                double confidence = Math.min(state.confidence(), edge.confidence() == null ? 1.0 : edge.confidence());
                InformationNature nature = mergeNature(state.nature(), edge.nature());
                if (sinkIds.contains(next)) {
                    ProgramGraphNode sink = nodes.get(next);
                    findings.add(new SecurityPath(
                            source.id(), source.label(), sink.id(), sink.label(), nodesPath, edgePath,
                            sanitizers, !sanitizers.isEmpty(), confidence, nature));
                    if (findings.size() >= request.maxResults()) break;
                }
                String stateKey = next + "|" + sanitizers;
                if (seenStates.add(stateKey)) {
                    queue.addLast(new State(next, nodesPath, edgePath, sanitizers, state.depth() + 1, confidence, nature));
                }
            }
        }
        if (!queue.isEmpty()) limitations.add("MAX_RESULTS_REACHED");
    }

    private static Map<String, List<ProgramGraphEdge>> outgoing(ProgramGraph graph) {
        Map<String, List<ProgramGraphEdge>> result = new LinkedHashMap<>();
        graph.edges().stream()
                .filter(edge -> FLOW_KINDS.contains(edge.kind()))
                .sorted(Comparator.comparing(ProgramGraphEdge::id))
                .forEach(edge -> result.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>()).add(edge));
        return result;
    }

    private static InformationNature mergeNature(InformationNature left, InformationNature right) {
        if (left == InformationNature.HEURISTIC || right == InformationNature.HEURISTIC) return InformationNature.HEURISTIC;
        if (left == InformationNature.DERIVED || right == InformationNature.DERIVED) return InformationNature.DERIVED;
        return InformationNature.FACTUAL;
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(value);
        return List.copyOf(result);
    }

    private record State(
            String nodeId,
            List<String> nodeIds,
            List<String> edgeIds,
            List<String> sanitizerNodeIds,
            int depth,
            double confidence,
            InformationNature nature
    ) {
    }

    public record SecurityRequest(String sourceNodeId, int maxDepth, int maxResults) {
        public SecurityRequest {
            if (sourceNodeId != null && sourceNodeId.isBlank()) throw new IllegalArgumentException("sourceNodeId must be null or non-blank");
            if (maxDepth < 1 || maxDepth > 32) throw new IllegalArgumentException("maxDepth must be between 1 and 32");
            if (maxResults < 1 || maxResults > 1000) throw new IllegalArgumentException("maxResults must be between 1 and 1000");
        }
    }

    public record SecurityPath(
            String sourceNodeId,
            String sourceLabel,
            String sinkNodeId,
            String sinkLabel,
            List<String> nodePath,
            List<String> edgePath,
            List<String> sanitizerNodeIds,
            boolean sanitizedPathObserved,
            double confidence,
            InformationNature nature
    ) {
        public SecurityPath {
            nodePath = List.copyOf(nodePath);
            edgePath = List.copyOf(edgePath);
            sanitizerNodeIds = List.copyOf(sanitizerNodeIds);
        }
    }

    public record SecurityReport(
            String projectId,
            String snapshotId,
            SecurityRequest request,
            List<SecurityPath> observedPaths,
            List<String> limitations
    ) {
        public SecurityReport {
            observedPaths = List.copyOf(observedPaths);
            limitations = List.copyOf(limitations);
        }
    }
}
