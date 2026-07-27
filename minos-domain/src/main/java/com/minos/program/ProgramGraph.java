package com.minos.program;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, provider-independent M19 program graph view. */
public record ProgramGraph(
        String projectId,
        String snapshotId,
        Set<ProgramGraphCapability> capabilities,
        List<ProgramGraphNode> nodes,
        List<ProgramGraphEdge> edges,
        List<String> limitations
) {
    public ProgramGraph {
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);

        Set<String> nodeIds = new HashSet<>();
        for (ProgramGraphNode node : nodes) {
            Objects.requireNonNull(node, "nodes must not contain null");
            if (!projectId.equals(node.projectId())) {
                throw new IllegalArgumentException("node belongs to another project: " + node.id());
            }
            if (!nodeIds.add(node.id())) {
                throw new IllegalArgumentException("duplicate program node id: " + node.id());
            }
        }
        Set<String> edgeIds = new HashSet<>();
        for (ProgramGraphEdge edge : edges) {
            Objects.requireNonNull(edge, "edges must not contain null");
            if (!projectId.equals(edge.projectId())) {
                throw new IllegalArgumentException("edge belongs to another project: " + edge.id());
            }
            if (!edgeIds.add(edge.id())) {
                throw new IllegalArgumentException("duplicate program edge id: " + edge.id());
            }
            if (!nodeIds.contains(edge.sourceNodeId()) || !nodeIds.contains(edge.targetNodeId())) {
                throw new IllegalArgumentException("program edge references an unknown node: " + edge.id());
            }
        }
    }

    public boolean supports(ProgramGraphCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
