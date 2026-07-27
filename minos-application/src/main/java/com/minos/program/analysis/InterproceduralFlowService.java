package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic, bounded traversal over call/data-flow program edges. */
public final class InterproceduralFlowService {

    private static final Set<ProgramEdgeKind> FLOW_KINDS = EnumSet.of(
            ProgramEdgeKind.CALL,
            ProgramEdgeKind.DEF_USE,
            ProgramEdgeKind.DATA_FLOW,
            ProgramEdgeKind.ARGUMENT_FLOW,
            ProgramEdgeKind.RETURN_FLOW,
            ProgramEdgeKind.TAINT_FLOW);

    public FlowResult traverse(ProgramGraph graph, FlowRequest request) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(request, "request");
        Map<String, List<ProgramGraphEdge>> outgoing = new LinkedHashMap<>();
        graph.edges().stream()
                .filter(edge -> FLOW_KINDS.contains(edge.kind()))
                .sorted(Comparator.comparing(ProgramGraphEdge::id))
                .forEach(edge -> outgoing.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>()).add(edge));

        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(new State(request.startNodeId(), List.of(request.startNodeId()), List.of(), 0));
        List<FlowPath> paths = new ArrayList<>();
        Set<String> limitations = new LinkedHashSet<>(graph.limitations());
        boolean cycleObserved = false;
        boolean depthReached = false;

        while (!queue.isEmpty() && paths.size() < request.maxResults()) {
            State state = queue.removeFirst();
            if (state.depth() >= request.maxDepth()) {
                if (!outgoing.getOrDefault(state.nodeId(), List.of()).isEmpty()) depthReached = true;
                continue;
            }
            for (ProgramGraphEdge edge : outgoing.getOrDefault(state.nodeId(), List.of())) {
                boolean cycle = state.nodeIds().contains(edge.targetNodeId());
                List<String> nodeIds = append(state.nodeIds(), edge.targetNodeId());
                List<String> edgeIds = append(state.edgeIds(), edge.id());
                paths.add(new FlowPath(nodeIds, edgeIds, cycle));
                if (cycle) {
                    cycleObserved = true;
                } else {
                    queue.addLast(new State(edge.targetNodeId(), nodeIds, edgeIds, state.depth() + 1));
                }
                if (paths.size() >= request.maxResults()) break;
            }
        }

        if (cycleObserved) limitations.add("CYCLE_OBSERVED");
        if (depthReached) limitations.add("MAX_DEPTH_REACHED");
        if (!queue.isEmpty()) limitations.add("MAX_RESULTS_REACHED");
        limitations.add("DYNAMIC_DISPATCH_NOT_PROVEN");
        return new FlowResult(graph.projectId(), graph.snapshotId(), request, List.copyOf(paths),
                limitations.stream().sorted().toList());
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(value);
        return List.copyOf(result);
    }

    private record State(String nodeId, List<String> nodeIds, List<String> edgeIds, int depth) {
    }

    public record FlowRequest(String startNodeId, int maxDepth, int maxResults) {
        public FlowRequest {
            if (startNodeId == null || startNodeId.isBlank()) throw new IllegalArgumentException("startNodeId must not be blank");
            if (maxDepth < 1 || maxDepth > 32) throw new IllegalArgumentException("maxDepth must be between 1 and 32");
            if (maxResults < 1 || maxResults > 10_000) throw new IllegalArgumentException("maxResults must be between 1 and 10000");
        }
    }

    public record FlowPath(List<String> nodeIds, List<String> edgeIds, boolean cycle) {
        public FlowPath {
            nodeIds = List.copyOf(nodeIds);
            edgeIds = List.copyOf(edgeIds);
        }
    }

    public record FlowResult(
            String projectId,
            String snapshotId,
            FlowRequest request,
            List<FlowPath> paths,
            List<String> limitations
    ) {
        public FlowResult {
            paths = List.copyOf(paths);
            limitations = List.copyOf(limitations);
        }
    }
}
