package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramGraphComposerLimitationsTest {

    private static final String PROJECT = "project-1";
    private static final String SNAPSHOT = "snapshot-1";
    private static final Origin ORIGIN = new Origin("test-provider", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void nodeOnlyTruncationDoesNotClaimEdgeLimit() {
        ProgramGraph graph = new ProgramGraphComposer().compose(
                PROJECT,
                SNAPSHOT,
                List.of(fragment()),
                new ProgramGraphBudget(2, 10));

        assertTrue(graph.limitations().contains("PROGRAM_GRAPH_NODE_LIMIT_REACHED"));
        assertFalse(graph.limitations().contains("PROGRAM_GRAPH_EDGE_LIMIT_REACHED"));
    }

    @Test
    void edgeOnlyTruncationReportsOnlyEdgeLimit() {
        ProgramGraph graph = new ProgramGraphComposer().compose(
                PROJECT,
                SNAPSHOT,
                List.of(fragment()),
                new ProgramGraphBudget(3, 1));

        assertFalse(graph.limitations().contains("PROGRAM_GRAPH_NODE_LIMIT_REACHED"));
        assertTrue(graph.limitations().contains("PROGRAM_GRAPH_EDGE_LIMIT_REACHED"));
    }

    @Test
    void combinedTruncationReportsBothIndependentCauses() {
        ProgramGraph graph = new ProgramGraphComposer().compose(
                PROJECT,
                SNAPSHOT,
                List.of(fragment()),
                new ProgramGraphBudget(2, 1));

        assertTrue(graph.limitations().contains("PROGRAM_GRAPH_NODE_LIMIT_REACHED"));
        assertTrue(graph.limitations().contains("PROGRAM_GRAPH_EDGE_LIMIT_REACHED"));
    }

    private static ProgramGraph fragment() {
        List<ProgramGraphNode> nodes = List.of(node("n1"), node("n2"), node("n3"));
        List<ProgramGraphEdge> edges = List.of(
                edge("e12", "n1", "n2"),
                edge("e21", "n2", "n1"),
                edge("e23", "n2", "n3"));
        return new ProgramGraph(PROJECT, SNAPSHOT, Set.of(), nodes, edges, List.of());
    }

    private static ProgramGraphNode node(String id) {
        return new ProgramGraphNode(
                id,
                PROJECT,
                id,
                ProgramNodeKind.SYMBOL,
                id,
                null,
                InformationNature.FACTUAL,
                null,
                ORIGIN,
                List.of());
    }

    private static ProgramGraphEdge edge(String id, String source, String target) {
        return new ProgramGraphEdge(
                id,
                PROJECT,
                source,
                target,
                ProgramEdgeKind.DATA_FLOW,
                InformationNature.FACTUAL,
                null,
                ORIGIN,
                List.of());
    }
}
