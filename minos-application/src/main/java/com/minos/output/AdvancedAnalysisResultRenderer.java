package com.minos.output;

import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.analysis.AdvancedImpactService;
import com.minos.program.analysis.SecurityAnalysisService;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

/** Deterministic JSON renderer for additive M19 public surfaces. */
public final class AdvancedAnalysisResultRenderer {

    private AdvancedAnalysisResultRenderer() {
    }

    public static String renderProgramGraph(ProgramGraph graph) {
        return renderProgramGraph(graph, Long.MAX_VALUE);
    }

    /** Streams node/edge mappings into a bounded JSON encoder instead of materializing result lists. */
    public static String renderProgramGraph(ProgramGraph graph, long maximumUtf8Bytes) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", "1");
        map.put("projectId", graph.projectId());
        map.put("snapshotId", graph.snapshotId());
        map.put("capabilities", graph.capabilities().stream().map(Enum::name).sorted().toList());
        map.put("limitations", graph.limitations());
        map.put("nodes", mapped(graph.nodes(), AdvancedAnalysisResultRenderer::node));
        map.put("edges", mapped(graph.edges(), AdvancedAnalysisResultRenderer::edge));
        return DeterministicJson.render(map, maximumUtf8Bytes);
    }

    public static String renderAdvancedImpact(AdvancedImpactService.AdvancedImpactReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", "1");
        map.put("projectId", report.projectId());
        map.put("snapshotId", report.snapshotId());
        map.put("baselineImpactCount", report.baselineCount());
        map.put("baselineTestCount", report.baseline().potentiallyImpactedTests().size());
        map.put("advancedAddedCount", report.advancedAddedCount());
        map.put("totalImpactCount", report.totalCount());
        map.put("limitations", report.limitations());
        map.put("advancedAdded", report.advancedAdded().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("symbolId", item.symbolId());
            value.put("label", item.label());
            value.put("depth", item.depth());
            value.put("confidence", item.confidence());
            value.put("nature", item.nature().name());
            value.put("programEdgePath", item.programEdgePath());
            return value;
        }).toList());
        return DeterministicJson.render(map);
    }

    public static String renderSecurity(SecurityAnalysisService.SecurityReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", "1");
        map.put("projectId", report.projectId());
        map.put("snapshotId", report.snapshotId());
        map.put("limitations", report.limitations());
        map.put("observedPaths", report.observedPaths().stream().map(path -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sourceNodeId", path.sourceNodeId());
            value.put("sourceLabel", path.sourceLabel());
            value.put("sinkNodeId", path.sinkNodeId());
            value.put("sinkLabel", path.sinkLabel());
            value.put("nodePath", path.nodePath());
            value.put("edgePath", path.edgePath());
            value.put("sanitizerNodeIds", path.sanitizerNodeIds());
            value.put("sanitizedPathObserved", path.sanitizedPathObserved());
            value.put("confidence", path.confidence());
            value.put("nature", path.nature().name());
            return value;
        }).toList());
        return DeterministicJson.render(map);
    }

    private static Map<String, Object> node(ProgramGraphNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.id());
        map.put("symbolId", node.symbolId());
        map.put("kind", node.kind().name());
        map.put("label", node.label());
        map.put("nature", node.nature().name());
        map.put("confidence", node.confidence());
        map.put("providerId", node.origin().providerId());
        if (node.location() != null) {
            map.put("fileId", node.location().fileId());
            map.put("startLine", node.location().startLine());
            map.put("startColumn", node.location().startColumn());
        }
        return map;
    }

    private static Map<String, Object> edge(ProgramGraphEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", edge.id());
        map.put("sourceNodeId", edge.sourceNodeId());
        map.put("targetNodeId", edge.targetNodeId());
        map.put("kind", edge.kind().name());
        map.put("nature", edge.nature().name());
        map.put("confidence", edge.confidence());
        map.put("providerId", edge.origin().providerId());
        map.put("evidence", edge.evidence().stream().map(value -> value.description()).toList());
        return map;
    }

    private static <T, R> Iterable<R> mapped(Iterable<T> values, Function<T, R> mapper) {
        return () -> new Iterator<>() {
            private final Iterator<T> delegate = values.iterator();
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public R next() { return mapper.apply(delegate.next()); }
        };
    }
}
