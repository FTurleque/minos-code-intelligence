package com.minos.output;

import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic JSON projection shared by M20 transport surfaces. */
public final class SemanticAnalysisResultRenderer {

    private SemanticAnalysisResultRenderer() {
    }

    public static String renderIndexStatus(SemanticIndexService.Status value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", "1");
        map.put("mode", "SEMANTIC_INDEX");
        map.put("projectId", value.projectId());
        map.put("projectName", value.projectName());
        map.put("state", value.state());
        map.put("activeSnapshotId", value.activeSnapshotId());
        map.put("indexedSnapshotId", value.indexedSnapshotId());
        map.put("providerId", value.providerId());
        map.put("modelId", value.modelId());
        map.put("dimensions", value.dimensions());
        map.put("documentCount", value.documentCount());
        map.put("indexSizeBytes", value.indexSizeBytes());
        map.put("limitations", value.limitations());
        return DeterministicJson.render(map);
    }

    public static String renderSemantic(SemanticSearchService.SearchResponse value) {
        Map<String, Object> map = base(value.projectId(), value.snapshotId(), value.query(), "SEMANTIC");
        map.put("hits", value.hits().stream().map(hit -> {
            Map<String, Object> item = document(hit.document());
            item.put("score", hit.score());
            item.put("nature", hit.nature());
            item.put("providerId", hit.providerId());
            item.put("modelId", hit.modelId());
            return item;
        }).toList());
        map.put("limitations", value.limitations());
        map.put("latencyMillis", value.latencyMillis());
        return DeterministicJson.render(map);
    }

    public static String renderHybrid(HybridSearchService.HybridResponse value) {
        Map<String, Object> map = base(value.projectId(), value.snapshotId(), value.query(), "HYBRID");
        map.put("semanticAvailable", value.semanticAvailable());
        map.put("hits", value.hits().stream().map(SemanticAnalysisResultRenderer::hybridHit).toList());
        map.put("limitations", value.limitations());
        map.put("latencyMillis", value.latencyMillis());
        return DeterministicJson.render(map);
    }

    public static String renderContext(HybridContextBuilder.ContextResponse value) {
        Map<String, Object> map = base(value.projectId(), value.snapshotId(), value.query(), "HYBRID_CONTEXT");
        map.put("maxTokens", value.maxTokens());
        map.put("usedTokens", value.usedTokens());
        map.put("truncated", value.truncated());
        map.put("items", value.items().stream().map(item -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stableKey", item.stableKey());
            result.put("kind", item.kind());
            result.put("sourceId", item.sourceId());
            result.put("fileId", item.fileId());
            result.put("startLine", item.startLine());
            result.put("endLine", item.endLine());
            result.put("content", item.content());
            result.put("rankingScore", item.rankingScore());
            result.put("rankingMode", item.rankingMode());
            result.put("signals", item.signals().stream().map(SemanticAnalysisResultRenderer::signal).toList());
            result.put("estimatedTokens", item.estimatedTokens());
            result.put("truncated", item.truncated());
            return result;
        }).toList());
        map.put("limitations", value.limitations());
        return DeterministicJson.render(map);
    }

    private static Map<String, Object> hybridHit(HybridSearchService.HybridHit hit) {
        Map<String, Object> map = document(hit.document());
        map.put("score", hit.score());
        map.put("lexicalScore", hit.lexicalScore());
        map.put("graphScore", hit.graphScore());
        map.put("semanticScore", hit.semanticScore());
        map.put("rankingMode", hit.rankingMode());
        map.put("signals", hit.signals().stream().map(SemanticAnalysisResultRenderer::signal).toList());
        return map;
    }

    private static Map<String, Object> signal(HybridSearchService.RankingSignal signal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", signal.type());
        map.put("score", signal.score());
        map.put("nature", signal.nature());
        return map;
    }

    private static Map<String, Object> document(SemanticDocument document) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", document.id());
        map.put("stableKey", document.stableKey());
        map.put("kind", document.kind());
        map.put("sourceId", document.sourceId());
        map.put("fileId", document.fileId());
        map.put("startLine", document.startLine());
        map.put("endLine", document.endLine());
        map.put("checksum", document.checksum());
        return map;
    }

    private static Map<String, Object> base(String projectId, String snapshotId, String query, String mode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractVersion", "1");
        map.put("projectId", projectId);
        map.put("snapshotId", snapshotId);
        map.put("query", query);
        map.put("mode", mode);
        return map;
    }
}
