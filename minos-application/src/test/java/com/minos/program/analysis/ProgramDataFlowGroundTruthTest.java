package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramDataFlowGroundTruthTest {

    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void exactDefUseGroundTruthHasPerfectPrecisionAndRecall() {
        ProgramGraph graph = new ProgramGraph(
                "project", "snapshot",
                Set.of(ProgramGraphCapability.LOCAL_DATA_FLOW),
                List.of(
                        node("variable:def", ProgramNodeKind.VARIABLE, "value definition"),
                        node("variable:use", ProgramNodeKind.VARIABLE, "value use")),
                List.of(new ProgramGraphEdge(
                        "def-use:1", "project", "variable:def", "variable:use",
                        ProgramEdgeKind.DEF_USE, InformationNature.FACTUAL, null, ORIGIN, List.of())),
                List.of("CONTROLLED_DEF_USE_GROUND_TRUTH"));

        ProgramGraphEvaluator.Evaluation evaluation = new ProgramGraphEvaluator().evaluate(
                graph,
                ProgramEdgeKind.DEF_USE,
                Set.of(new ProgramGraphEvaluator.EdgeTruth(
                        "variable:def", "variable:use", ProgramEdgeKind.DEF_USE)));

        assertTrue(evaluation.perfect());
    }

    private static ProgramGraphNode node(String id, ProgramNodeKind kind, String label) {
        return new ProgramGraphNode(
                id, "project", null, kind, label, null,
                InformationNature.FACTUAL, null, ORIGIN, List.of());
    }
}
