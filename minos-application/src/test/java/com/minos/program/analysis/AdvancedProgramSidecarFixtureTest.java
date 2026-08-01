package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedProgramSidecarFixtureTest {

    @Test
    void versionedSidecarFixtureHasPerfectGroundTruthForEveryAdvertisedAdvancedFlow() throws Exception {
        Path root = Path.of("fixtures/m21/advanced-program-sidecar/project").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root));
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        RegisteredProject project = new RegisteredProject(UUID.randomUUID(), root, "advanced-sidecar-fixture", Optional.empty(), now, now);
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), "snapshot-s7", List.of(), List.of(), List.of());

        ProgramGraph graph = new FileProgramGraphProvider().analyze(project, snapshot);

        assertEquals(Set.of(
                ProgramGraphCapability.CONTROL_FLOW,
                ProgramGraphCapability.LOCAL_DATA_FLOW,
                ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW,
                ProgramGraphCapability.SECURITY_TAINT), graph.capabilities());
        assertPerfect(graph, ProgramEdgeKind.CONTROL_FLOW, Set.of(
                truth("block:entry", "block:branch", ProgramEdgeKind.CONTROL_FLOW),
                truth("block:branch", "block:exit", ProgramEdgeKind.CONTROL_FLOW)));
        assertPerfect(graph, ProgramEdgeKind.DEF_USE, Set.of(
                truth("var:def", "var:use", ProgramEdgeKind.DEF_USE)));
        assertPerfect(graph, ProgramEdgeKind.ARGUMENT_FLOW, Set.of(
                truth("arg:caller", "arg:callee", ProgramEdgeKind.ARGUMENT_FLOW)));
        assertPerfect(graph, ProgramEdgeKind.RETURN_FLOW, Set.of(
                truth("ret:callee", "ret:caller", ProgramEdgeKind.RETURN_FLOW)));
        assertPerfect(graph, ProgramEdgeKind.TAINT_FLOW, Set.of(
                truth("security:source", "security:sanitizer", ProgramEdgeKind.TAINT_FLOW),
                truth("security:sanitizer", "security:sink", ProgramEdgeKind.TAINT_FLOW)));

        assertEquals(1, graph.nodes().stream().filter(node -> node.kind() == ProgramNodeKind.SOURCE).count());
        assertEquals(1, graph.nodes().stream().filter(node -> node.kind() == ProgramNodeKind.SANITIZER).count());
        assertEquals(1, graph.nodes().stream().filter(node -> node.kind() == ProgramNodeKind.SINK).count());
        assertTrue(graph.limitations().contains("ADVANCED_PROGRAM_FACTS_PROVIDER_ASSERTED"));
    }

    private static void assertPerfect(
            ProgramGraph graph,
            ProgramEdgeKind kind,
            Set<ProgramGraphEvaluator.EdgeTruth> truth
    ) {
        ProgramGraphEvaluator.Evaluation evaluation = new ProgramGraphEvaluator().evaluate(graph, kind, truth);
        assertTrue(evaluation.perfect(), () -> kind + " precision=" + evaluation.precision() + " recall=" + evaluation.recall());
    }

    private static ProgramGraphEvaluator.EdgeTruth truth(String source, String target, ProgramEdgeKind kind) {
        return new ProgramGraphEvaluator.EdgeTruth(source, target, kind);
    }
}
