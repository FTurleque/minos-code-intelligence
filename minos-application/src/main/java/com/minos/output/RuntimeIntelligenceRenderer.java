package com.minos.output;

import com.minos.dynamic.RuntimeIntelligenceService.HotPath;
import com.minos.dynamic.RuntimeIntelligenceService.ImportResult;
import com.minos.dynamic.RuntimeIntelligenceService.ObservedCall;
import com.minos.dynamic.RuntimeIntelligenceService.RuntimeReport;
import com.minos.dynamic.RuntimeIntelligenceService.SessionView;
import com.minos.dynamic.RuntimeIntelligenceService.SymbolRuntimeReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic JSON renderer for M26 runtime evidence. */
public final class RuntimeIntelligenceRenderer {

    private RuntimeIntelligenceRenderer() { }

    public static String renderImport(ImportResult value) {
        Map<String, Object> map = common(value.projectId().toString(), value.projectName(), value.snapshotId(),
                value.nature(), value.exhaustive(), value.limitations());
        map.put("sessionId", value.sessionId());
        map.put("sourceSha256", value.sourceSha256());
        map.put("sourceBytes", value.sourceBytes());
        map.put("observationCount", value.observationCount());
        map.put("resolvedReferences", value.resolvedReferences());
        map.put("unresolvedReferences", value.unresolvedReferences());
        map.put("ambiguousReferences", value.ambiguousReferences());
        map.put("alreadyPresent", value.alreadyPresent());
        map.put("importedAt", value.importedAt().toString());
        return DeterministicJson.render(map);
    }

    public static String renderSessions(List<SessionView> sessions) {
        return DeterministicJson.render(Map.of(
                "nature", "OBSERVED_PARTIAL",
                "exhaustive", false,
                "sessions", sessions.stream().map(RuntimeIntelligenceRenderer::sessionMap).toList(),
                "limitations", List.of("absence of a session or observation is never proof of non-execution")
        ));
    }

    public static String renderReport(RuntimeReport value) {
        Map<String, Object> map = common(value.projectId().toString(), value.projectName(), value.snapshotId(),
                value.nature(), value.exhaustive(), value.limitations());
        map.put("sessions", value.sessions().stream().map(RuntimeIntelligenceRenderer::sessionMap).toList());
        map.put("staticSymbolCount", value.staticSymbolCount());
        map.put("observedSymbolCount", value.observedSymbolCount());
        map.put("observedSymbolRatio", value.observedSymbolRatio());
        map.put("coveredLineCount", value.coveredLineCount());
        map.put("totalHits", value.totalHits());
        map.put("totalDurationNanos", value.totalDurationNanos());
        map.put("resolvedReferences", value.resolvedReferences());
        map.put("unresolvedReferences", value.unresolvedReferences());
        map.put("ambiguousReferences", value.ambiguousReferences());
        map.put("hotPaths", value.hotPaths().stream().map(RuntimeIntelligenceRenderer::hotPathMap).toList());
        map.put("observedCalls", value.observedCalls().stream().map(RuntimeIntelligenceRenderer::callMap).toList());
        return DeterministicJson.render(map);
    }

    public static String renderSymbol(SymbolRuntimeReport value) {
        Map<String, Object> map = common(value.projectId().toString(), value.projectName(), value.snapshotId(),
                value.nature(), value.exhaustive(), value.limitations());
        map.put("symbolId", value.symbolId());
        map.put("symbolKey", value.symbolKey());
        map.put("qualifiedName", value.qualifiedName());
        map.put("sessionIds", value.sessionIds());
        map.put("observedInSelectedSessions", value.observedInSelectedSessions());
        map.put("executionHits", value.executionHits());
        map.put("totalDurationNanos", value.totalDurationNanos());
        map.put("coveredLineHits", value.coveredLineHits());
        map.put("incomingCalls", value.incomingCalls().stream().map(RuntimeIntelligenceRenderer::callMap).toList());
        map.put("outgoingCalls", value.outgoingCalls().stream().map(RuntimeIntelligenceRenderer::callMap).toList());
        map.put("absenceMeaning", "NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS");
        return DeterministicJson.render(map);
    }

    private static Map<String, Object> common(
            String projectId, String projectName, String snapshotId,
            String nature, boolean exhaustive, List<String> limitations
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", projectId);
        map.put("projectName", projectName);
        map.put("snapshotId", snapshotId);
        map.put("nature", nature);
        map.put("exhaustive", exhaustive);
        map.put("limitations", limitations);
        return map;
    }

    private static Map<String, Object> sessionMap(SessionView value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", value.sessionId());
        map.put("snapshotId", value.snapshotId());
        map.put("startedAt", value.startedAt().toString());
        map.put("endedAt", value.endedAt().toString());
        map.put("collectorId", value.collectorId());
        map.put("collectorVersion", value.collectorVersion());
        map.put("environment", value.environment());
        map.put("completeness", value.completeness());
        map.put("observationCount", value.observationCount());
        map.put("resolvedReferences", value.resolvedReferences());
        map.put("unresolvedReferences", value.unresolvedReferences());
        map.put("ambiguousReferences", value.ambiguousReferences());
        map.put("importedAt", value.importedAt().toString());
        map.put("sourceSha256", value.sourceSha256());
        map.put("activeSnapshotAligned", value.activeSnapshotAligned());
        return map;
    }

    private static Map<String, Object> hotPathMap(HotPath value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", value.type());
        map.put("key", value.key());
        map.put("sourceResolution", value.sourceResolution());
        map.put("sourceSymbolId", value.sourceSymbolId());
        map.put("targetResolution", value.targetResolution());
        map.put("targetSymbolId", value.targetSymbolId());
        map.put("hits", value.hits());
        map.put("totalDurationNanos", value.totalDurationNanos());
        return map;
    }

    private static Map<String, Object> callMap(ObservedCall value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", value.source());
        map.put("target", value.target());
        map.put("sourceResolution", value.sourceResolution());
        map.put("sourceSymbolId", value.sourceSymbolId());
        map.put("targetResolution", value.targetResolution());
        map.put("targetSymbolId", value.targetSymbolId());
        map.put("hits", value.hits());
        map.put("totalDurationNanos", value.totalDurationNanos());
        return map;
    }
}
