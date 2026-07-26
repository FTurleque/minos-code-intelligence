package com.minos.output;

import com.minos.domain.Symbol;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactPathStep;
import com.minos.impact.ImpactedSymbol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic transport-neutral rendering of M8 impact analysis. */
public final class ImpactResultRenderer {

    private ImpactResultRenderer() {
    }

    public static String render(ImpactAnalysisReport report, SymbolOutputFormat format) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(format, "format");
        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("projectId", report.projectId().toString());
            map.put("snapshotId", report.snapshotId());
            map.put("nature", report.nature().name());
            map.put("rootSymbol", symbolMap(report.rootSymbol()));
            map.put("maxDepth", report.request().maxDepth());
            map.put("maxResults", report.request().maxResults());
            map.put("impactCount", report.impacts().size());
            map.put("testCount", report.potentiallyImpactedTests().size());
            map.put("limitations", report.limitations().stream().map(Enum::name).toList());
            map.put("impacts", report.impacts().stream().map(ImpactResultRenderer::impactMap).toList());
            map.put("potentiallyImpactedTests", report.potentiallyImpactedTests().stream()
                    .map(ImpactResultRenderer::impactMap).toList());
            return DeterministicJson.render(map);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("root: ").append(display(report.rootSymbol())).append('\n');
        builder.append("snapshot: ").append(report.snapshotId()).append('\n');
        builder.append("impacts: ").append(report.impacts().size()).append('\n');
        builder.append("potentiallyImpactedTests: ").append(report.potentiallyImpactedTests().size()).append('\n');
        builder.append("limitations: ").append(report.limitations()).append('\n');
        for (ImpactedSymbol impact : report.impacts()) {
            builder.append("- ")
                    .append(impact.level()).append(" depth=").append(impact.depth())
                    .append(" confidence=").append(impact.confidence())
                    .append(" test=").append(impact.testImpact())
                    .append(" ").append(display(impact.symbol()))
                    .append(" path=").append(impact.path().stream()
                            .map(step -> step.relationshipKind() + ":" + step.relationshipId())
                            .toList())
                    .append('\n');
        }
        if (!builder.isEmpty()) {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private static Map<String, Object> impactMap(ImpactedSymbol impact) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbolMap(impact.symbol()));
        map.put("level", impact.level().name());
        map.put("depth", impact.depth());
        map.put("confidence", impact.confidence());
        map.put("nature", impact.nature().name());
        map.put("testImpact", impact.testImpact());
        map.put("path", impact.path().stream().map(ImpactResultRenderer::stepMap).toList());
        return map;
    }

    private static Map<String, Object> stepMap(ImpactPathStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("changedSymbolId", step.changedSymbolId());
        map.put("impactedSymbolId", step.impactedSymbolId());
        map.put("relationshipId", step.relationshipId());
        map.put("relationshipKind", step.relationshipKind().name());
        map.put("relationshipNature", step.relationshipNature().name());
        map.put("confidence", step.confidence());
        return map;
    }

    private static Map<String, Object> symbolMap(Symbol symbol) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", symbol.id());
        map.put("name", symbol.name());
        map.put("qualifiedName", symbol.qualifiedName());
        map.put("kind", symbol.kind().name());
        map.put("signature", symbol.signature());
        map.put("fileId", symbol.fileId());
        map.put("moduleId", symbol.moduleId());
        return map;
    }

    private static String display(Symbol symbol) {
        return (symbol.qualifiedName() == null ? symbol.name() : symbol.qualifiedName())
                + " [" + symbol.id() + "]";
    }
}
