package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministically composes provider graph fragments and rejects conflicting stable ids. */
public final class ProgramGraphComposer {

    public ProgramGraph compose(String projectId, String snapshotId, List<ProgramGraph> fragments) {
        return compose(projectId, snapshotId, fragments, new ProgramGraphBudget(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    public ProgramGraph compose(
            String projectId,
            String snapshotId,
            List<ProgramGraph> fragments,
            ProgramGraphBudget budget
    ) {
        Objects.requireNonNull(fragments, "fragments");
        Objects.requireNonNull(budget, "budget");
        Map<String, ProgramGraphNode> nodes = new LinkedHashMap<>();
        Map<String, ProgramGraphEdge> edges = new LinkedHashMap<>();
        Set<ProgramGraphCapability> capabilities = new LinkedHashSet<>();
        Set<String> limitations = new LinkedHashSet<>();

        for (ProgramGraph fragment : fragments) {
            Objects.requireNonNull(fragment, "fragments must not contain null");
            if (!projectId.equals(fragment.projectId()) || !snapshotId.equals(fragment.snapshotId())) {
                throw new IllegalArgumentException("program graph fragment targets another project/snapshot");
            }
            capabilities.addAll(fragment.capabilities());
            limitations.addAll(fragment.limitations());
            for (ProgramGraphNode node : fragment.nodes().stream().sorted(Comparator.comparing(ProgramGraphNode::id)).toList()) {
                ProgramGraphNode existing = nodes.get(node.id());
                if (existing != null) {
                    if (!existing.equals(node)) throw conflict("node", node.id());
                    continue;
                }
                if (nodes.size() >= budget.maxNodes()) {
                    limitations.add("PROGRAM_GRAPH_NODE_LIMIT_REACHED");
                    continue;
                }
                nodes.put(node.id(), node);
            }
        }

        Set<String> nodeIds = Set.copyOf(nodes.keySet());
        outer:
        for (ProgramGraph fragment : fragments) {
            for (ProgramGraphEdge edge : fragment.edges().stream().sorted(Comparator.comparing(ProgramGraphEdge::id)).toList()) {
                if (!nodeIds.contains(edge.sourceNodeId()) || !nodeIds.contains(edge.targetNodeId())) continue;
                ProgramGraphEdge existing = edges.get(edge.id());
                if (existing != null) {
                    if (!existing.equals(edge)) throw conflict("edge", edge.id());
                    continue;
                }
                if (edges.size() >= budget.maxEdges()) {
                    limitations.add("PROGRAM_GRAPH_EDGE_LIMIT_REACHED");
                    break outer;
                }
                edges.put(edge.id(), edge);
            }
        }

        if (!nodes.isEmpty()) capabilities.add(ProgramGraphCapability.CPG);
        List<ProgramGraphNode> orderedNodes = new ArrayList<>(nodes.values());
        orderedNodes.sort(Comparator.comparing(ProgramGraphNode::id));
        List<ProgramGraphEdge> orderedEdges = new ArrayList<>(edges.values());
        orderedEdges.sort(Comparator.comparing(ProgramGraphEdge::id));
        return new ProgramGraph(
                projectId,
                snapshotId,
                capabilities,
                orderedNodes,
                orderedEdges,
                limitations.stream().sorted().toList());
    }

    private static IllegalArgumentException conflict(String kind, String id) {
        return new IllegalArgumentException("conflicting program " + kind + " id: " + id);
    }
}
