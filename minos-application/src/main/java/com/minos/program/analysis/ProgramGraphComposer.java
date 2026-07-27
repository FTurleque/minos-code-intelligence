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
        Objects.requireNonNull(fragments, "fragments");
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
            fragment.nodes().forEach(node -> merge(nodes, node.id(), node, "node"));
            fragment.edges().forEach(edge -> merge(edges, edge.id(), edge, "edge"));
        }

        if (!nodes.isEmpty()) {
            capabilities.add(ProgramGraphCapability.CPG);
        }
        List<ProgramGraphNode> orderedNodes = new ArrayList<>(nodes.values());
        orderedNodes.sort(Comparator.comparing(ProgramGraphNode::id));
        List<ProgramGraphEdge> orderedEdges = new ArrayList<>(edges.values());
        orderedEdges.sort(Comparator.comparing(ProgramGraphEdge::id));
        List<String> orderedLimitations = limitations.stream().sorted().toList();
        return new ProgramGraph(projectId, snapshotId, capabilities, orderedNodes, orderedEdges, orderedLimitations);
    }

    private static <T> void merge(Map<String, T> values, String id, T value, String kind) {
        T existing = values.putIfAbsent(id, value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException("conflicting program " + kind + " id: " + id);
        }
    }
}
