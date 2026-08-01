package com.minos.program.analysis;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.SymbolLocation;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic node/edge emitter shared by the decomposed Java analyses. */
final class JavaProgramGraphContext {

    static final double CFG_CONFIDENCE = 1.0;
    static final double DEF_USE_CONFIDENCE = 0.90;
    static final double INTERPROCEDURAL_CONFIDENCE = 0.85;
    static final double SECURITY_CONFIDENCE = 0.90;

    private static final String PROVIDER_VERSION = "1";

    private final String projectId;
    private final String snapshotId;
    private final SourcePositions positions;
    private final Origin astOrigin;
    private final Origin derivedOrigin;
    private final Map<String, ProgramGraphNode> nodes = new LinkedHashMap<>();
    private final Map<String, ProgramGraphEdge> edges = new LinkedHashMap<>();
    private final Set<String> limitations = new LinkedHashSet<>();

    JavaProgramGraphContext(
            String projectId,
            String snapshotId,
            SourcePositions positions,
            String runId
    ) {
        this.projectId = projectId;
        this.snapshotId = snapshotId;
        this.positions = positions;
        this.astOrigin = new Origin(
                JavaSourceProgramGraphProvider.PROVIDER_ID,
                "JAVA_COMPILER_AST",
                PROVIDER_VERSION,
                runId,
                OriginType.AST);
        this.derivedOrigin = new Origin(
                JavaSourceProgramGraphProvider.PROVIDER_ID,
                "JAVA_COMPILER_AST",
                PROVIDER_VERSION,
                runId,
                OriginType.DERIVED_BY_MINOS);
    }

    String projectId() {
        return projectId;
    }

    Origin derivedOrigin() {
        return derivedOrigin;
    }

    Map<String, ProgramGraphNode> nodes() {
        return nodes;
    }

    Map<String, ProgramGraphEdge> edges() {
        return edges;
    }

    Set<String> limitations() {
        return limitations;
    }

    void addLimitation(String limitation) {
        limitations.add(limitation);
    }

    ProgramGraphNode factualNode(
            String id,
            ProgramNodeKind kind,
            String label,
            SymbolLocation location
    ) {
        return new ProgramGraphNode(
                id,
                projectId,
                null,
                kind,
                label,
                location,
                InformationNature.FACTUAL,
                null,
                astOrigin,
                List.of());
    }

    String definitionNode(JavaParsedUnit unit, Tree tree, String name) {
        String id = id("def", unit, tree, name);
        addNode(factualNode(
                id,
                ProgramNodeKind.VARIABLE,
                label("definition " + name, unit, tree),
                location(unit, tree)));
        return id;
    }

    void addNode(ProgramGraphNode node) {
        ProgramGraphNode existing = nodes.putIfAbsent(node.id(), node);
        if (existing != null && !existing.equals(node)) {
            throw new IllegalStateException("conflicting Java provider node id: " + node.id());
        }
    }

    void addDerivedEdge(
            String prefix,
            String source,
            String target,
            ProgramEdgeKind kind,
            double confidence,
            String description,
            SymbolLocation location
    ) {
        String edgeId = "java:" + prefix + ":" + source + "->" + target;
        Evidence evidence = new Evidence(
                EvidenceType.DERIVATION_PATH,
                description,
                null,
                null,
                location,
                confidence);
        ProgramGraphEdge edge = new ProgramGraphEdge(
                edgeId,
                projectId,
                source,
                target,
                kind,
                InformationNature.DERIVED,
                confidence,
                derivedOrigin,
                List.of(evidence));
        ProgramGraphEdge existing = edges.putIfAbsent(edge.id(), edge);
        if (existing != null && !existing.equals(edge)) {
            throw new IllegalStateException("conflicting Java provider edge id: " + edge.id());
        }
    }

    String id(String prefix, JavaParsedUnit unit, Tree tree, String suffix) {
        long start = positions.getStartPosition(unit.tree(), tree);
        long end = positions.getEndPosition(unit.tree(), tree);
        return "java:" + prefix + ":" + unit.fileId() + ":" + Math.max(0L, start) + ":" + Math.max(0L, end)
                + ":" + compact(suffix);
    }

    String label(String value, JavaParsedUnit unit, Tree tree) {
        SymbolLocation location = location(unit, tree);
        return value + " @ " + unit.fileId() + ":" + location.startLine() + ":" + location.startColumn();
    }

    SymbolLocation location(JavaParsedUnit unit, Tree tree) {
        long start = positions.getStartPosition(unit.tree(), tree);
        long end = positions.getEndPosition(unit.tree(), tree);
        if (start < 0L) {
            start = 0L;
        }
        if (end <= start) {
            end = start + 1L;
        }
        long last = Math.max(start, end - 1L);
        long startLine = unit.tree().getLineMap().getLineNumber(start);
        long startColumn = unit.tree().getLineMap().getColumnNumber(start) - 1L;
        long endLine = unit.tree().getLineMap().getLineNumber(last);
        long endColumn = unit.tree().getLineMap().getColumnNumber(last);
        return new SymbolLocation(
                unit.fileId(),
                safeInt(startLine, 1),
                safeInt(startColumn, 0),
                safeInt(endLine, safeInt(startLine, 1)),
                safeInt(endColumn, 0),
                PositionEncoding.UTF16_CODE_UNITS);
    }

    ProgramGraph toGraph(Set<ProgramGraphCapability> capabilities) {
        return new ProgramGraph(
                projectId,
                snapshotId,
                Set.copyOf(capabilities),
                nodes.values().stream().sorted(Comparator.comparing(ProgramGraphNode::id)).toList(),
                edges.values().stream().sorted(Comparator.comparing(ProgramGraphEdge::id)).toList(),
                limitations.stream().sorted().toList());
    }

    private static int safeInt(long value, int fallback) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            return fallback;
        }
        return (int) value;
    }

    private static String compact(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
