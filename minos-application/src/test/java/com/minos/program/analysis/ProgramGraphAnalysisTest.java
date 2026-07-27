package com.minos.program.analysis;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramGraphAnalysisTest {

    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void relationshipProviderProducesExactCallGraphAndExplicitPotentialDataFlow() {
        UUID projectId = UUID.randomUUID();
        RegisteredProject project = project(projectId);
        Symbol caller = symbol(projectId, "caller", SymbolKind.METHOD);
        Symbol callee = symbol(projectId, "callee", SymbolKind.METHOD);
        Symbol variable = symbol(projectId, "value", SymbolKind.VARIABLE);
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot-call",
                List.of(caller, callee, variable),
                List.of(),
                List.of(
                        relationship(projectId, "r-call", caller.id(), callee.id(), RelationshipKind.CALLS),
                        relationship(projectId, "r-write", caller.id(), variable.id(), RelationshipKind.WRITES),
                        relationship(projectId, "r-read", callee.id(), variable.id(), RelationshipKind.READS)
                ));

        ProgramGraph graph = new RelationshipProgramGraphProvider().analyze(project, snapshot);

        assertTrue(graph.supports(ProgramGraphCapability.CALL_GRAPH));
        assertTrue(graph.supports(ProgramGraphCapability.LOCAL_DATA_FLOW));
        assertTrue(graph.limitations().contains("EXECUTION_ORDER_NOT_PROVEN"));
        assertEquals(1, graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.CALL).count());
        assertEquals(2, graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.DATA_FLOW).count());
        assertTrue(graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.DATA_FLOW)
                .allMatch(edge -> edge.nature() == InformationNature.DERIVED));

        ProgramGraphEvaluator.Evaluation evaluation = new ProgramGraphEvaluator().evaluate(
                graph,
                ProgramEdgeKind.CALL,
                Set.of(new ProgramGraphEvaluator.EdgeTruth("symbol:caller", "symbol:callee", ProgramEdgeKind.CALL)));
        assertTrue(evaluation.perfect());
    }

    @Test
    void controlledCfgCoversBranchLoopAndExceptionEdgesWithPerfectGroundTruth() {
        ProgramGraph graph = fixtureGraph("p", "s");
        Set<ProgramGraphEvaluator.EdgeTruth> truth = Set.of(
                truth("block:entry", "block:true", ProgramEdgeKind.CONTROL_FLOW),
                truth("block:entry", "block:false", ProgramEdgeKind.CONTROL_FLOW),
                truth("block:true", "block:loop", ProgramEdgeKind.CONTROL_FLOW),
                truth("block:loop", "block:loop", ProgramEdgeKind.CONTROL_FLOW),
                truth("block:false", "block:catch", ProgramEdgeKind.CONTROL_FLOW),
                truth("block:catch", "block:exit", ProgramEdgeKind.CONTROL_FLOW)
        );
        ProgramGraphEvaluator.Evaluation evaluation = new ProgramGraphEvaluator().evaluate(
                graph, ProgramEdgeKind.CONTROL_FLOW, truth);
        assertTrue(evaluation.perfect());
        assertTrue(graph.supports(ProgramGraphCapability.CONTROL_FLOW));
    }

    @Test
    void cpgCompositionRejectsConflictingStableIds() {
        ProgramGraph left = new ProgramGraph("p", "s", Set.of(),
                List.of(node("same", null, ProgramNodeKind.BASIC_BLOCK, "left")), List.of(), List.of());
        ProgramGraph right = new ProgramGraph("p", "s", Set.of(),
                List.of(node("same", null, ProgramNodeKind.BASIC_BLOCK, "right")), List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGraphComposer().compose("p", "s", List.of(left, right)));
    }

    @Test
    void interproceduralTraversalIsBoundedAndReportsCycles() {
        ProgramGraph graph = new ProgramGraph(
                "p", "s",
                Set.of(ProgramGraphCapability.CALL_GRAPH, ProgramGraphCapability.LOCAL_DATA_FLOW),
                List.of(
                        node("symbol:a", "a", ProgramNodeKind.SYMBOL, "a"),
                        node("symbol:b", "b", ProgramNodeKind.SYMBOL, "b"),
                        node("symbol:c", "c", ProgramNodeKind.SYMBOL, "c")),
                List.of(
                        edge("e1", "symbol:a", "symbol:b", ProgramEdgeKind.CALL),
                        edge("e2", "symbol:b", "symbol:c", ProgramEdgeKind.DATA_FLOW),
                        edge("e3", "symbol:c", "symbol:a", ProgramEdgeKind.CALL)),
                List.of());

        InterproceduralFlowService.FlowResult result = new InterproceduralFlowService().traverse(
                graph, new InterproceduralFlowService.FlowRequest("symbol:a", 8, 100));
        assertFalse(result.paths().isEmpty());
        assertTrue(result.limitations().contains("CYCLE_OBSERVED"));
        assertTrue(result.limitations().contains("DYNAMIC_DISPATCH_NOT_PROVEN"));
    }

    @Test
    void impactV2AddsProgramFlowBeyondM8AndSecurityReportsSanitizedObservedPath(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        RegisteredProject project = registry.registerProject(root, "fixture");
        Symbol rootSymbol = symbol(project.id(), "root", SymbolKind.METHOD);
        Symbol advancedSymbol = symbol(project.id(), "advanced", SymbolKind.METHOD);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        store.publish(project.id(), "snapshot-advanced", List.of(rootSymbol, advancedSymbol), List.of(), List.of());

        ProgramGraphProvider provider = new FixtureProvider();
        ProgramGraphService graphs = new ProgramGraphService(registry, store, List.of(provider));
        AdvancedImpactService impacts = new AdvancedImpactService(new LocalProjectImpactQuery(registry, store), graphs);

        AdvancedImpactService.AdvancedImpactReport impact = impacts.analyze(
                project.id().toString(), new ImpactAnalysisRequest(rootSymbol.id(), 8, 100));
        assertEquals(0, impact.baselineCount());
        assertEquals(1, impact.advancedAddedCount());
        assertEquals("advanced", impact.advancedAdded().getFirst().symbolId());

        SecurityAnalysisService.SecurityReport security = new SecurityAnalysisService(graphs).analyze(
                project.id().toString(), new SecurityAnalysisService.SecurityRequest(null, 8, 100));
        assertEquals(1, security.observedPaths().size());
        assertTrue(security.observedPaths().getFirst().sanitizedPathObserved());
        assertEquals(List.of("security:sanitizer"), security.observedPaths().getFirst().sanitizerNodeIds());
        assertTrue(security.limitations().contains("ABSENCE_OF_PATH_IS_NOT_PROOF_OF_SAFETY"));
    }

    private static final class FixtureProvider implements ProgramGraphProvider {
        @Override
        public String id() {
            return "fixture-program-provider";
        }

        @Override
        public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
            String projectId = project.id().toString();
            ProgramGraph base = fixtureGraph(projectId, snapshot.snapshotId());
            List<ProgramGraphNode> nodes = new java.util.ArrayList<>(base.nodes());
            nodes.add(node(projectId, "symbol:root", "root", ProgramNodeKind.SYMBOL, "root"));
            nodes.add(node(projectId, "symbol:advanced", "advanced", ProgramNodeKind.SYMBOL, "advanced"));
            nodes.add(node(projectId, "security:source", "root", ProgramNodeKind.SOURCE, "request-input"));
            nodes.add(node(projectId, "security:sanitizer", null, ProgramNodeKind.SANITIZER, "sanitize"));
            nodes.add(node(projectId, "security:sink", "advanced", ProgramNodeKind.SINK, "database-write"));
            List<ProgramGraphEdge> edges = new java.util.ArrayList<>(base.edges());
            edges.add(edge(projectId, "impact-flow", "symbol:root", "symbol:advanced", ProgramEdgeKind.DATA_FLOW));
            edges.add(edge(projectId, "taint-1", "security:source", "security:sanitizer", ProgramEdgeKind.TAINT_FLOW));
            edges.add(edge(projectId, "taint-2", "security:sanitizer", "security:sink", ProgramEdgeKind.TAINT_FLOW));
            return new ProgramGraph(
                    projectId,
                    snapshot.snapshotId(),
                    Set.of(
                            ProgramGraphCapability.CONTROL_FLOW,
                            ProgramGraphCapability.LOCAL_DATA_FLOW,
                            ProgramGraphCapability.SECURITY_TAINT),
                    nodes,
                    edges,
                    List.of("FIXTURE_CONTROLLED_GROUND_TRUTH"));
        }
    }

    private static ProgramGraph fixtureGraph(String projectId, String snapshotId) {
        return new ProgramGraph(
                projectId,
                snapshotId,
                Set.of(ProgramGraphCapability.CONTROL_FLOW, ProgramGraphCapability.LOCAL_DATA_FLOW),
                List.of(
                        node(projectId, "block:entry", null, ProgramNodeKind.BASIC_BLOCK, "entry"),
                        node(projectId, "block:true", null, ProgramNodeKind.BASIC_BLOCK, "if-true"),
                        node(projectId, "block:false", null, ProgramNodeKind.BASIC_BLOCK, "if-false"),
                        node(projectId, "block:loop", null, ProgramNodeKind.BASIC_BLOCK, "loop"),
                        node(projectId, "block:catch", null, ProgramNodeKind.BASIC_BLOCK, "exception-handler"),
                        node(projectId, "block:exit", null, ProgramNodeKind.BASIC_BLOCK, "exit")),
                List.of(
                        edge(projectId, "cfg-1", "block:entry", "block:true", ProgramEdgeKind.CONTROL_FLOW),
                        edge(projectId, "cfg-2", "block:entry", "block:false", ProgramEdgeKind.CONTROL_FLOW),
                        edge(projectId, "cfg-3", "block:true", "block:loop", ProgramEdgeKind.CONTROL_FLOW),
                        edge(projectId, "cfg-4", "block:loop", "block:loop", ProgramEdgeKind.CONTROL_FLOW),
                        edge(projectId, "cfg-5", "block:false", "block:catch", ProgramEdgeKind.CONTROL_FLOW),
                        edge(projectId, "cfg-6", "block:catch", "block:exit", ProgramEdgeKind.CONTROL_FLOW)),
                List.of());
    }

    private static RegisteredProject project(UUID id) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new RegisteredProject(id, Path.of("."), "fixture", Optional.empty(), now, now);
    }

    private static Symbol symbol(UUID projectId, String id, SymbolKind kind) {
        return new Symbol(
                id,
                "fixture:" + id,
                SymbolIdentityQuality.CANONICAL,
                projectId.toString(),
                "module",
                "src/Fixture.java",
                null,
                kind,
                id,
                "fixture." + id,
                kind == SymbolKind.VARIABLE ? null : "()",
                "java",
                null,
                ResolutionStatus.RESOLVED,
                ORIGIN,
                false,
                false,
                Set.of());
    }

    private static Relationship relationship(UUID projectId, String id, String source, String target, RelationshipKind kind) {
        return new Relationship(
                id,
                projectId.toString(),
                new CodeEntityRef(CodeEntityType.SYMBOL, source),
                new CodeEntityRef(CodeEntityType.SYMBOL, target),
                null,
                kind,
                null,
                ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL,
                null,
                ORIGIN,
                List.of());
    }

    private static ProgramGraphEvaluator.EdgeTruth truth(String source, String target, ProgramEdgeKind kind) {
        return new ProgramGraphEvaluator.EdgeTruth(source, target, kind);
    }

    private static ProgramGraphNode node(String id, String symbolId, ProgramNodeKind kind, String label) {
        return node("p", id, symbolId, kind, label);
    }

    private static ProgramGraphNode node(String projectId, String id, String symbolId, ProgramNodeKind kind, String label) {
        return new ProgramGraphNode(id, projectId, symbolId, kind, label, null, InformationNature.FACTUAL, null, ORIGIN, List.of());
    }

    private static ProgramGraphEdge edge(String id, String source, String target, ProgramEdgeKind kind) {
        return edge("p", id, source, target, kind);
    }

    private static ProgramGraphEdge edge(String projectId, String id, String source, String target, ProgramEdgeKind kind) {
        return new ProgramGraphEdge(id, projectId, source, target, kind, InformationNature.FACTUAL, null, ORIGIN, List.of());
    }
}
