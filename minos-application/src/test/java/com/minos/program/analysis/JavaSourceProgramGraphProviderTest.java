package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceProgramGraphProviderTest {

    private static final Path FIXTURE_ROOT = Path.of("fixtures/m22/java-advanced-provider/project")
            .toAbsolutePath().normalize();

    @Test
    void cfgFixtureHasPerfectControlledPrecisionAndRecall() throws Exception {
        ProgramGraph graph = graphForFixture("src/main/java/demo/CfgFixture.java");

        assertTrue(graph.supports(ProgramGraphCapability.CONTROL_FLOW));
        ProgramGraphNode first = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg EXPRESSION_STATEMENT", 5);
        ProgramGraphNode decision = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg IF", 6);
        ProgramGraphNode second = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg EXPRESSION_STATEMENT", 7);
        ProgramGraphNode third = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg EXPRESSION_STATEMENT", 9);
        ProgramGraphNode loop = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg WHILE_LOOP", 11);
        ProgramGraphNode fourth = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg EXPRESSION_STATEMENT", 12);
        ProgramGraphNode fifth = node(graph, ProgramNodeKind.BASIC_BLOCK, "cfg EXPRESSION_STATEMENT", 14);

        assertPerfect(graph, ProgramEdgeKind.CONTROL_FLOW, Set.of(
                truth(first, decision, ProgramEdgeKind.CONTROL_FLOW),
                truth(decision, second, ProgramEdgeKind.CONTROL_FLOW),
                truth(decision, third, ProgramEdgeKind.CONTROL_FLOW),
                truth(second, loop, ProgramEdgeKind.CONTROL_FLOW),
                truth(third, loop, ProgramEdgeKind.CONTROL_FLOW),
                truth(loop, fourth, ProgramEdgeKind.CONTROL_FLOW),
                truth(fourth, loop, ProgramEdgeKind.CONTROL_FLOW),
                truth(loop, fifth, ProgramEdgeKind.CONTROL_FLOW)));
        assertProviderDerivedEdges(graph, ProgramEdgeKind.CONTROL_FLOW);
    }

    @Test
    void defUseFixtureHasPerfectControlledPrecisionAndRecall() throws Exception {
        ProgramGraph graph = graphForFixture("src/main/java/demo/DefUseFixture.java");

        assertTrue(graph.supports(ProgramGraphCapability.LOCAL_DATA_FLOW));
        ProgramGraphNode parameter = node(graph, ProgramNodeKind.PARAMETER, "parameter run[0] value", 4);
        ProgramGraphNode valueUse = node(graph, ProgramNodeKind.VARIABLE, "use value", 5);
        ProgramGraphNode copyDefinition = node(graph, ProgramNodeKind.VARIABLE, "definition copy", 5);
        ProgramGraphNode copyUse = node(graph, ProgramNodeKind.VARIABLE, "use copy", 6);

        assertPerfect(graph, ProgramEdgeKind.DEF_USE, Set.of(
                truth(parameter, valueUse, ProgramEdgeKind.DEF_USE),
                truth(copyDefinition, copyUse, ProgramEdgeKind.DEF_USE)));
        assertTrue(graph.limitations().contains("JAVA_LOCAL_DATA_FLOW_NAME_BASED_WITHIN_METHOD"));
        assertProviderDerivedEdges(graph, ProgramEdgeKind.DEF_USE);
    }

    @Test
    void interproceduralFixtureHasPerfectArgumentAndReturnPrecisionRecall() throws Exception {
        ProgramGraph graph = graphForFixture("src/main/java/demo/InterproceduralFixture.java");

        assertTrue(graph.supports(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW));
        ProgramGraphNode callerArgument = node(graph, ProgramNodeKind.PARAMETER, "argument callee[0]", 9);
        ProgramGraphNode calleeParameter = node(graph, ProgramNodeKind.PARAMETER, "parameter callee[0] value", 4);
        ProgramGraphNode calleeReturn = node(graph, ProgramNodeKind.RETURN_VALUE, "return callee", 5);
        ProgramGraphNode callerResult = node(graph, ProgramNodeKind.RETURN_VALUE, "call-result callee", 9);

        assertPerfect(graph, ProgramEdgeKind.ARGUMENT_FLOW, Set.of(
                truth(callerArgument, calleeParameter, ProgramEdgeKind.ARGUMENT_FLOW)));
        assertPerfect(graph, ProgramEdgeKind.RETURN_FLOW, Set.of(
                truth(calleeReturn, callerResult, ProgramEdgeKind.RETURN_FLOW)));
        assertTrue(graph.limitations().contains("JAVA_INTERPROCEDURAL_UNIQUE_NAME_ARITY_ONLY"));
        assertProviderDerivedEdges(graph, ProgramEdgeKind.ARGUMENT_FLOW);
        assertProviderDerivedEdges(graph, ProgramEdgeKind.RETURN_FLOW);
    }

    @Test
    void securityFixtureHasPerfectConfiguredTaintPrecisionRecall() throws Exception {
        ProgramGraph graph = graphForFixture("src/main/java/demo/SecurityFixture.java");

        assertTrue(graph.supports(ProgramGraphCapability.SECURITY_TAINT));
        ProgramGraphNode source = node(graph, ProgramNodeKind.SOURCE, "source source", 16);
        ProgramGraphNode sanitizer = node(graph, ProgramNodeKind.SANITIZER, "sanitizer sanitize", 17);
        ProgramGraphNode sink = node(graph, ProgramNodeKind.SINK, "sink sink", 18);

        assertPerfect(graph, ProgramEdgeKind.TAINT_FLOW, Set.of(
                truth(source, sanitizer, ProgramEdgeKind.TAINT_FLOW),
                truth(sanitizer, sink, ProgramEdgeKind.TAINT_FLOW)));
        assertTrue(graph.limitations().contains("JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY"));
        assertProviderDerivedEdges(graph, ProgramEdgeKind.TAINT_FLOW);
    }

    @Test
    void nonJavaSnapshotAndSyntaxFailureStayFailClosed(@TempDir Path temp) throws Exception {
        RegisteredProject project = project(Files.createDirectories(temp.resolve("project")));
        JavaSourceProgramGraphProvider provider = new JavaSourceProgramGraphProvider();
        CodeKnowledgeSnapshot empty = new CodeKnowledgeSnapshot(project.id(), "snapshot-empty", List.of(), List.of(), List.of());

        ProgramGraph notApplicable = provider.analyze(project, empty);
        assertTrue(notApplicable.capabilities().isEmpty());
        assertEquals(List.of("JAVA_ADVANCED_PROVIDER_NOT_APPLICABLE"), notApplicable.limitations());

        String fileId = "src/Bad.java";
        Path source = project.rootPath().resolve(fileId);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Bad { void broken( { }", StandardCharsets.UTF_8);
        CodeKnowledgeSnapshot broken = snapshot(project, "snapshot-bad", fileId);

        ProgramGraph parseFailure = provider.analyze(project, broken);
        assertTrue(parseFailure.capabilities().isEmpty());
        assertTrue(parseFailure.nodes().isEmpty());
        assertEquals(List.of("JAVA_ADVANCED_PROVIDER_PARSE_FAILED"), parseFailure.limitations());
    }

    @Test
    void securityIsNeverClaimedWithoutExplicitRulesAndCacheTracksSourceChanges(@TempDir Path temp) throws Exception {
        RegisteredProject project = project(Files.createDirectories(temp.resolve("project")));
        String fileId = "src/Simple.java";
        Path source = project.rootPath().resolve(fileId);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Simple { int run(int value) { int copy = value; return copy; } }", StandardCharsets.UTF_8);
        CodeKnowledgeSnapshot snapshot = snapshot(project, "snapshot-1", fileId);
        JavaSourceProgramGraphProvider provider = new JavaSourceProgramGraphProvider();

        String before = provider.cacheKey(project, snapshot);
        ProgramGraph graph = provider.analyze(project, snapshot);
        assertFalse(graph.supports(ProgramGraphCapability.SECURITY_TAINT));
        assertTrue(graph.limitations().contains("JAVA_SECURITY_RULES_NOT_CONFIGURED"));

        Files.writeString(source, "class Simple { int run(int value) { int copy = value + 1; return copy; } }", StandardCharsets.UTF_8);
        String after = provider.cacheKey(project, snapshot);
        assertNotEquals(before, after);
    }

    private static ProgramGraph graphForFixture(String fileId) throws Exception {
        assertTrue(Files.isDirectory(FIXTURE_ROOT));
        RegisteredProject project = project(FIXTURE_ROOT);
        return new JavaSourceProgramGraphProvider().analyze(project, snapshot(project, "snapshot-m22", fileId));
    }

    private static RegisteredProject project(Path root) {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new RegisteredProject(UUID.randomUUID(), root, "m22-java-fixture", Optional.empty(), now, now);
    }

    private static CodeKnowledgeSnapshot snapshot(RegisteredProject project, String snapshotId, String fileId) {
        SymbolLocation location = new SymbolLocation(fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        Origin origin = new Origin("m22-fixture", "FIXTURE", "1", snapshotId, OriginType.OTHER);
        Symbol symbol = new Symbol(
                "symbol-" + Integer.toUnsignedString(fileId.hashCode()),
                "fixture:" + fileId,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                null,
                fileId,
                null,
                SymbolKind.CLASS,
                Path.of(fileId).getFileName().toString().replace(".java", ""),
                null,
                null,
                "java",
                location,
                ResolutionStatus.RESOLVED,
                origin,
                false,
                false,
                Set.of());
        return new CodeKnowledgeSnapshot(project.id(), snapshotId, List.of(symbol), List.of(), List.of());
    }

    private static ProgramGraphNode node(
            ProgramGraph graph,
            ProgramNodeKind kind,
            String labelPrefix,
            int startLine
    ) {
        List<ProgramGraphNode> matches = graph.nodes().stream()
                .filter(node -> node.kind() == kind)
                .filter(node -> node.label().startsWith(labelPrefix + " @ "))
                .filter(node -> node.location() != null && node.location().startLine() == startLine)
                .toList();
        assertEquals(1, matches.size(), () -> "expected one node " + kind + " " + labelPrefix + " at line " + startLine
                + " but got " + matches.stream().map(ProgramGraphNode::label).toList());
        return matches.getFirst();
    }

    private static ProgramGraphEvaluator.EdgeTruth truth(
            ProgramGraphNode source,
            ProgramGraphNode target,
            ProgramEdgeKind kind
    ) {
        return new ProgramGraphEvaluator.EdgeTruth(source.id(), target.id(), kind);
    }

    private static void assertPerfect(
            ProgramGraph graph,
            ProgramEdgeKind kind,
            Set<ProgramGraphEvaluator.EdgeTruth> expected
    ) {
        ProgramGraphEvaluator.Evaluation evaluation = new ProgramGraphEvaluator().evaluate(graph, kind, expected);
        assertTrue(evaluation.perfect(), () -> kind + " precision=" + evaluation.precision()
                + " recall=" + evaluation.recall() + " expected=" + evaluation.expected()
                + " observed=" + evaluation.observed());
    }

    private static void assertProviderDerivedEdges(ProgramGraph graph, ProgramEdgeKind kind) {
        assertTrue(graph.edges().stream()
                .filter(edge -> edge.kind() == kind)
                .allMatch(edge -> edge.nature() == InformationNature.DERIVED
                        && JavaSourceProgramGraphProvider.PROVIDER_ID.equals(edge.origin().providerId())
                        && !edge.evidence().isEmpty()));
    }
}
