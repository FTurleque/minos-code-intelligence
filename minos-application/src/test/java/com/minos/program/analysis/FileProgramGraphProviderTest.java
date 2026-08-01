package com.minos.program.analysis;

import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProgramGraphProviderTest {

    @Test
    void loadsSnapshotAlignedCfgDataFlowInterproceduralAndSecurityFacts(@TempDir Path temp) throws Exception {
        RegisteredProject project = project(temp.resolve("project"));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), "snapshot-1", List.of(), List.of(), List.of());
        writeFullSidecar(project.rootPath(), "snapshot-1");

        ProgramGraph graph = new FileProgramGraphProvider().analyze(project, snapshot);

        assertEquals(Set.of(
                ProgramGraphCapability.CONTROL_FLOW,
                ProgramGraphCapability.LOCAL_DATA_FLOW,
                ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW,
                ProgramGraphCapability.SECURITY_TAINT), graph.capabilities());
        assertTrue(graph.limitations().contains("ADVANCED_PROGRAM_SIDECAR_V1"));
        assertTrue(graph.limitations().contains("ADVANCED_PROGRAM_FACTS_PROVIDER_ASSERTED"));
        assertEquals(1, graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.CONTROL_FLOW).count());
        assertEquals(1, graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.DEF_USE).count());
        assertEquals(1, graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.ARGUMENT_FLOW).count());
        assertEquals(2, graph.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.TAINT_FLOW).count());
        assertTrue(graph.edges().stream().allMatch(edge -> edge.nature() == com.minos.domain.InformationNature.FACTUAL));
    }

    @Test
    void refusesCapabilityClaimsWithoutMatchingFacts(@TempDir Path temp) throws Exception {
        RegisteredProject project = project(temp.resolve("project"));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), "snapshot-1", List.of(), List.of(), List.of());
        writeSidecar(
                project.rootPath(),
                "snapshot-1",
                "CONTROL_FLOW,INTERPROCEDURAL_DATA_FLOW",
                List.of(node("block:entry", "BASIC_BLOCK", "entry"), node("block:exit", "BASIC_BLOCK", "exit")),
                List.of(edge("cfg:1", "block:entry", "block:exit", "CONTROL_FLOW")));

        IOException failure = assertThrows(IOException.class, () -> new FileProgramGraphProvider().analyze(project, snapshot));
        assertTrue(failure.getMessage().contains("INTERPROCEDURAL_DATA_FLOW"));
        assertTrue(failure.getMessage().contains("ARGUMENT_FLOW or RETURN_FLOW"));
    }

    @Test
    void staleSidecarNeverContributesCapabilities(@TempDir Path temp) throws Exception {
        RegisteredProject project = project(temp.resolve("project"));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), "snapshot-current", List.of(), List.of(), List.of());
        writeSidecar(
                project.rootPath(),
                "snapshot-old",
                "CONTROL_FLOW",
                List.of(node("block:entry", "BASIC_BLOCK", "entry"), node("block:exit", "BASIC_BLOCK", "exit")),
                List.of(edge("cfg:1", "block:entry", "block:exit", "CONTROL_FLOW")));

        ProgramGraph graph = new FileProgramGraphProvider().analyze(project, snapshot);

        assertTrue(graph.capabilities().isEmpty());
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
        assertEquals(List.of("ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT"), graph.limitations());
    }

    @Test
    void serviceDoesNotInferInterproceduralCapabilityAndInvalidatesCacheWhenSidecarChanges(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        RegisteredProject project = registry.registerProject(root, "fixture");
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        store.publish(project.id(), "snapshot-1", List.of(), List.of(), List.of());

        writeSidecar(
                root,
                "snapshot-1",
                "CALL_GRAPH,LOCAL_DATA_FLOW",
                List.of(
                        node("symbol:a", "SYMBOL", "a"),
                        node("symbol:b", "SYMBOL", "b"),
                        node("var:def", "VARIABLE", "def"),
                        node("var:use", "VARIABLE", "use")),
                List.of(
                        edge("call:1", "symbol:a", "symbol:b", "CALL"),
                        edge("def-use:1", "var:def", "var:use", "DEF_USE")));

        ProgramGraphService service = new ProgramGraphService(registry, store, List.of(new RelationshipProgramGraphProvider()));
        ProgramGraph before = service.getGraph(project.id().toString());
        assertTrue(before.supports(ProgramGraphCapability.CALL_GRAPH));
        assertTrue(before.supports(ProgramGraphCapability.LOCAL_DATA_FLOW));
        assertFalse(before.supports(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW));
        assertTrue(before.limitations().contains("INTERPROCEDURAL_DATA_FLOW_UNAVAILABLE"));

        writeSidecar(
                root,
                "snapshot-1",
                "CALL_GRAPH,LOCAL_DATA_FLOW,INTERPROCEDURAL_DATA_FLOW",
                List.of(
                        node("symbol:a", "SYMBOL", "a"),
                        node("symbol:b", "SYMBOL", "b"),
                        node("var:def", "VARIABLE", "def"),
                        node("var:use", "VARIABLE", "use"),
                        node("arg:a", "PARAMETER", "argument"),
                        node("arg:b", "PARAMETER", "parameter")),
                List.of(
                        edge("call:1", "symbol:a", "symbol:b", "CALL"),
                        edge("def-use:1", "var:def", "var:use", "DEF_USE"),
                        edge("arg:1", "arg:a", "arg:b", "ARGUMENT_FLOW")));

        ProgramGraph after = service.getGraph(project.id().toString());
        assertTrue(after.supports(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW));
        assertFalse(after.limitations().contains("INTERPROCEDURAL_DATA_FLOW_UNAVAILABLE"));
        assertEquals(1, after.edges().stream().filter(edge -> edge.kind() == ProgramEdgeKind.ARGUMENT_FLOW).count());
    }

    @Test
    void minosDirectoryIsHardIgnoredByProjectFingerprint(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".minos/program-graph-v1"));
        Files.writeString(root.resolve(".minos/program-graph-v1/nodes.tsv"), "ignored", StandardCharsets.UTF_8);

        var fingerprint = new com.minos.incremental.ProjectFingerprintService().capture(root);

        assertEquals(List.of("App.java"), fingerprint.files().stream().map(com.minos.incremental.FileFingerprint::relativePath).toList());
    }

    private static RegisteredProject project(Path root) throws IOException {
        Path projectRoot = Files.createDirectories(root);
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        return new RegisteredProject(UUID.randomUUID(), projectRoot, "fixture", Optional.empty(), now, now);
    }

    private static void writeFullSidecar(Path root, String snapshotId) throws IOException {
        writeSidecar(
                root,
                snapshotId,
                "CONTROL_FLOW,LOCAL_DATA_FLOW,INTERPROCEDURAL_DATA_FLOW,SECURITY_TAINT",
                List.of(
                        node("block:entry", "BASIC_BLOCK", "entry"),
                        node("block:exit", "BASIC_BLOCK", "exit"),
                        node("var:def", "VARIABLE", "value definition"),
                        node("var:use", "VARIABLE", "value use"),
                        node("arg:caller", "PARAMETER", "caller argument"),
                        node("arg:callee", "PARAMETER", "callee parameter"),
                        node("security:source", "SOURCE", "request input"),
                        node("security:sanitizer", "SANITIZER", "sanitize"),
                        node("security:sink", "SINK", "database write")),
                List.of(
                        edge("cfg:1", "block:entry", "block:exit", "CONTROL_FLOW"),
                        edge("def-use:1", "var:def", "var:use", "DEF_USE"),
                        edge("argument:1", "arg:caller", "arg:callee", "ARGUMENT_FLOW"),
                        edge("taint:1", "security:source", "security:sanitizer", "TAINT_FLOW"),
                        edge("taint:2", "security:sanitizer", "security:sink", "TAINT_FLOW")));
    }

    private static void writeSidecar(
            Path root,
            String snapshotId,
            String capabilities,
            List<String> nodes,
            List<String> edges
    ) throws IOException {
        Path directory = Files.createDirectories(root.resolve(FileProgramGraphProvider.RELATIVE_DIRECTORY));
        String metadata = """
                formatVersion=1
                snapshotId=%s
                providerId=controlled-static-analyzer
                providerType=STATIC_ANALYZER
                providerVersion=1.0.0
                indexRunId=run-1
                capabilities=%s
                """.formatted(snapshotId, capabilities);
        Files.writeString(directory.resolve(FileProgramGraphProvider.METADATA_FILE), metadata, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(FileProgramGraphProvider.NODES_FILE),
                "id\tsymbolId\tkind\tlabel\tfileId\tstartLine\tstartColumn\tendLine\tendColumn\tpositionEncoding\n"
                        + String.join("\n", nodes) + "\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(FileProgramGraphProvider.EDGES_FILE),
                "id\tsourceNodeId\ttargetNodeId\tkind\n" + String.join("\n", edges) + "\n", StandardCharsets.UTF_8);
    }

    private static String node(String id, String kind, String label) {
        return id + "\t\t" + kind + "\t" + label + "\t\t\t\t\t\t";
    }

    private static String edge(String id, String source, String target, String kind) {
        return id + "\t" + source + "\t" + target + "\t" + kind;
    }
}
