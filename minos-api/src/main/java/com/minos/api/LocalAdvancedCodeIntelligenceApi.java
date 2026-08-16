package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.domain.SymbolLocation;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.analysis.AdvancedImpactService;
import com.minos.program.analysis.SecurityAnalysisService;

import java.util.Objects;

/** Local M19 implementation backed only by shared MinosApplication services. */
public final class LocalAdvancedCodeIntelligenceApi implements AdvancedCodeIntelligenceApi {

    private final MinosApplication application;

    public LocalAdvancedCodeIntelligenceApi(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    @Override
    public ProgramGraphDto getProgramGraph(String projectIdentifier, ProgramGraphQuery query) throws MinosApi.MinosApiException {
        return execute(() -> graph(application.programGraphService().getGraph(
                projectIdentifier, required(query, "query").maxNodes(), query.maxEdges())));
    }

    @Override
    public AdvancedImpactDto analyzeImpactV2(String projectIdentifier, AdvancedImpactQuery query) throws MinosApi.MinosApiException {
        return execute(() -> {
            AdvancedImpactQuery value = required(query, "query");
            return impact(application.advancedImpactService().analyze(
                    projectIdentifier,
                    new ImpactAnalysisRequest(value.symbolId(), value.maxDepth(), value.maxResults())));
        });
    }

    @Override
    public SecurityReportDto analyzeSecurityPaths(String projectIdentifier, SecurityQuery query) throws MinosApi.MinosApiException {
        return execute(() -> {
            SecurityQuery value = required(query, "query");
            return security(application.securityAnalysisService().analyze(
                    projectIdentifier,
                    new SecurityAnalysisService.SecurityRequest(value.sourceNodeId(), value.maxDepth(), value.maxResults())));
        });
    }

    private static ProgramGraphDto graph(ProgramGraph graph) {
        return new ProgramGraphDto(
                graph.projectId(),
                graph.snapshotId(),
                graph.capabilities().stream().map(Enum::name).sorted().toList(),
                graph.nodes().stream().map(LocalAdvancedCodeIntelligenceApi::node).toList(),
                graph.edges().stream().map(LocalAdvancedCodeIntelligenceApi::edge).toList(),
                graph.limitations());
    }

    private static ProgramNodeDto node(ProgramGraphNode node) {
        return new ProgramNodeDto(
                node.id(), node.symbolId(), node.kind().name(), node.label(), location(node.location()),
                node.nature().name(), node.confidence(), node.origin().providerId());
    }

    private static ProgramEdgeDto edge(ProgramGraphEdge edge) {
        return new ProgramEdgeDto(
                edge.id(), edge.sourceNodeId(), edge.targetNodeId(), edge.kind().name(), edge.nature().name(),
                edge.confidence(), edge.origin().providerId(), edge.evidence().stream().map(value -> value.description()).toList());
    }

    private static AdvancedImpactDto impact(AdvancedImpactService.AdvancedImpactReport report) {
        return new AdvancedImpactDto(
                report.projectId(), report.snapshotId(), report.baselineCount(), report.advancedAddedCount(), report.totalCount(),
                report.advancedAdded().stream().map(item -> new AdvancedImpactItemDto(
                        item.symbolId(), item.label(), item.depth(), item.confidence(), item.nature().name(), item.programEdgePath())).toList(),
                report.limitations());
    }

    private static SecurityReportDto security(SecurityAnalysisService.SecurityReport report) {
        return new SecurityReportDto(
                report.projectId(), report.snapshotId(),
                report.observedPaths().stream().map(path -> new SecurityPathDto(
                        path.sourceNodeId(), path.sourceLabel(), path.sinkNodeId(), path.sinkLabel(),
                        path.nodePath(), path.edgePath(), path.sanitizerNodeIds(), path.sanitizedPathObserved(),
                        path.confidence(), path.nature().name())).toList(),
                report.limitations());
    }

    private static LocationDto location(SymbolLocation location) {
        if (location == null) return null;
        return new LocationDto(
                location.fileId(), location.startLine(), location.startColumn(), location.endLine(), location.endColumn(),
                location.positionEncoding().name());
    }

    private static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static <T> T execute(MinosApiSupport.ApiCall<T> call) throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(call);
    }
}
