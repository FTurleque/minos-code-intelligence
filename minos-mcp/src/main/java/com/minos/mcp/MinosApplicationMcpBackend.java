package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectInspectionService;
import com.minos.application.ProjectQueryService;
import com.minos.application.ProviderPlatformService;
import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.context.CodeSearchCriteria;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.output.ArchitectureResultRenderer;
import com.minos.output.CodeIntelligenceResultRenderer;
import com.minos.output.CodeSearchRenderer;
import com.minos.output.DeterministicJson;
import com.minos.output.ImpactResultRenderer;
import com.minos.output.SymbolOutputFormat;
import com.minos.output.SymbolResultRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Production MCP backend that calls shared application services directly. */
final class MinosApplicationMcpBackend implements MinosMcpBackend {

    private static final int DEFAULT_SYMBOL_LIMIT = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 5;
    private static final int DEFAULT_SEARCH_DEPTH = 1;
    private static final int DEFAULT_SEARCH_USAGES = 3;
    private static final int DEFAULT_SEARCH_RELATIONSHIPS = 10;
    private static final int DEFAULT_CONTEXT_LINES = 2;
    private static final int DEFAULT_MAX_TOKENS = 4_000;
    private static final int DEFAULT_IMPACT_DEPTH = 4;
    private static final int DEFAULT_IMPACT_LIMIT = 200;

    private final MinosApplication application;
    private final ProjectInspectionService projects;
    private final ProjectQueryService queries;
    private final ProviderPlatformService providerPlatform;

    MinosApplicationMcpBackend(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
        this.projects = application.projectInspectionService();
        this.queries = application.projectQueryService();
        this.providerPlatform = ProviderPlatformService.defaults(application);
    }

    @Override
    public String projectStructure(String project) throws Exception {
        ProjectInspectionService.ProjectView view = projects.inspectProject(project);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", view.id());
        map.put("name", view.name());
        map.put("rootPath", view.rootPath());
        map.put("rootAvailable", view.rootAvailable());
        map.put("languages", view.languages());
        map.put("buildSystems", view.buildSystems());
        map.put("moduleCount", view.moduleCount());
        map.put("indexState", view.indexState());
        map.put("activeSnapshotId", view.activeSnapshotId());
        map.put("lastSuccessfulIndexAt", view.lastSuccessfulIndexAt());
        map.put("providerId", view.providerId());
        map.put("providerVersion", view.providerVersion());
        map.put("providerProfiles", providerProfiles());
        return DeterministicJson.render(map);
    }

    @Override
    public String indexStatus(String project) throws Exception {
        ProjectInspectionService.ProjectView view = projects.inspectProject(project);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", view.id());
        map.put("projectName", view.name());
        map.put("state", view.indexState());
        map.put("activeSnapshotId", view.activeSnapshotId());
        map.put("lastSuccessfulIndexAt", view.lastSuccessfulIndexAt());
        map.put("providerId", view.providerId());
        map.put("providerVersion", view.providerVersion());
        map.put("providerProfiles", providerProfiles());
        return DeterministicJson.render(map);
    }

    @Override
    public String searchCode(SearchRequest request) throws Exception {
        CodeSearchCriteria criteria = new CodeSearchCriteria(
                symbolCriteria(request.query(), request.qualifiedName(), request.kind(), request.module(), request.limit()),
                request.depth(), request.usages(), request.relationships(), request.contextLines(),
                request.maxTokens(), request.includeSource());
        return CodeSearchRenderer.render(queries.searchCode(request.project(), criteria), SymbolOutputFormat.JSON);
    }

    @Override
    public String findSymbols(SymbolSearchRequest request) throws Exception {
        return SymbolResultRenderer.render(
                queries.findSymbols(request.project(), symbolCriteria(
                        request.query(), request.qualifiedName(), request.kind(), request.module(), request.limit())),
                SymbolOutputFormat.JSON);
    }

    @Override
    public String findUsages(RelationRequest request) throws Exception {
        return CodeIntelligenceResultRenderer.renderUsages(
                queries.findUsages(request.project(), request.symbolId(), request.limit()), SymbolOutputFormat.JSON);
    }

    @Override
    public String findRelationships(RelationshipOperation operation, RelationRequest request) throws Exception {
        RelationshipKind kind;
        boolean incoming;
        switch (operation) {
            case IMPLEMENTATIONS -> { kind = RelationshipKind.IMPLEMENTS; incoming = true; }
            case CALLERS -> { kind = RelationshipKind.CALLS; incoming = true; }
            case CALLEES -> { kind = RelationshipKind.CALLS; incoming = false; }
            case DEPENDENCIES -> { kind = RelationshipKind.DEPENDS_ON; incoming = false; }
            case DEPENDENTS -> { kind = RelationshipKind.DEPENDS_ON; incoming = true; }
            case RELATED_TESTS -> { kind = RelationshipKind.RELATED_TEST; incoming = true; }
            default -> throw new IllegalStateException("unsupported relationship operation: " + operation);
        }
        CodeEntityRef anchor = new CodeEntityRef(CodeEntityType.SYMBOL, request.symbolId());
        RelationshipSearchCriteria criteria = incoming
                ? RelationshipSearchCriteria.incoming(anchor, Set.of(kind), request.limit())
                : RelationshipSearchCriteria.outgoing(anchor, Set.of(kind), request.limit());
        return CodeIntelligenceResultRenderer.renderRelationships(
                queries.findRelationships(request.project(), criteria), SymbolOutputFormat.JSON);
    }

    @Override
    public String symbolContext(SymbolContextRequest request) throws Exception {
        return searchCode(new SearchRequest(
                request.project(), request.query(), request.qualifiedName(), request.kind(), request.module(), 1,
                request.depth(), DEFAULT_SEARCH_USAGES, DEFAULT_SEARCH_RELATIONSHIPS,
                request.contextLines(), request.maxTokens(), request.includeSource()));
    }

    @Override
    public String moduleContext(String project, String module) throws Exception {
        return ArchitectureResultRenderer.renderModule(
                application.architectureQuery().getModuleContext(project, module), SymbolOutputFormat.JSON);
    }

    @Override
    public String architecture(String project) throws Exception {
        return ArchitectureResultRenderer.render(
                application.architectureQuery().getArchitectureIntelligence(project), SymbolOutputFormat.JSON);
    }

    @Override
    public String architectureGraph(ArchitectureGraphRequest request) throws Exception {
        String format = request.format();
        if ("json".equals(format)) {
            return request.module() == null ? architecture(request.project()) : moduleContext(request.project(), request.module());
        }
        ArchitectureIntelligenceView view = application.architectureQuery().getArchitectureIntelligence(request.project());
        String moduleId = request.module() == null ? null : application.architectureQuery()
                .getModuleContext(request.project(), request.module()).module().id();
        ArchitectureResultRenderer.GraphFormat graphFormat = switch (format) {
            case "mermaid" -> ArchitectureResultRenderer.GraphFormat.MERMAID;
            case "dot" -> ArchitectureResultRenderer.GraphFormat.DOT;
            default -> throw new IllegalArgumentException("unsupported architecture graph format: " + format);
        };
        return ArchitectureResultRenderer.renderGraph(view, moduleId, graphFormat);
    }

    @Override
    public String impact(ImpactRequest request) throws Exception {
        return ImpactResultRenderer.render(
                application.impactQuery().analyzeImpact(
                        request.project(), new ImpactAnalysisRequest(request.symbolId(), request.depth(), request.limit())),
                SymbolOutputFormat.JSON);
    }

    private List<Map<String, Object>> providerProfiles() {
        return providerPlatform.listProviders().stream().map(value -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", value.id());
            map.put("version", value.version());
            map.put("languages", value.languages());
            map.put("buildSystems", value.buildSystems());
            map.put("capabilities", value.capabilities());
            map.put("conformanceScorePercent", value.conformanceScorePercent());
            map.put("limitations", value.limitations());
            map.put("runtimeState", value.runtimeState());
            map.put("runtimeDiagnostics", value.runtimeDiagnostics());
            return Map.copyOf(map);
        }).toList();
    }

    private static SymbolSearchCriteria symbolCriteria(String query, String qualifiedName, String kind, String module, int limit) {
        return new SymbolSearchCriteria(query, qualifiedName, parseKind(kind), module, limit);
    }

    private static SymbolKind parseKind(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return SymbolKind.valueOf(value.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported symbol kind: " + value, exception);
        }
    }

    static SearchRequest searchDefaults(
            String project, String query, String qualifiedName, String kind, String module,
            Integer limit, Integer depth, Integer usages, Integer relationships,
            Integer contextLines, Integer maxTokens, boolean includeSource
    ) {
        return new SearchRequest(
                project, query, qualifiedName, kind, module,
                limit == null ? DEFAULT_SEARCH_LIMIT : limit,
                depth == null ? DEFAULT_SEARCH_DEPTH : depth,
                usages == null ? DEFAULT_SEARCH_USAGES : usages,
                relationships == null ? DEFAULT_SEARCH_RELATIONSHIPS : relationships,
                contextLines == null ? DEFAULT_CONTEXT_LINES : contextLines,
                maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens,
                includeSource);
    }

    static SymbolSearchRequest symbolDefaults(
            String project, String query, String qualifiedName, String kind, String module, Integer limit
    ) {
        return new SymbolSearchRequest(project, query, qualifiedName, kind, module,
                limit == null ? DEFAULT_SYMBOL_LIMIT : limit);
    }

    static RelationRequest relationDefaults(String project, String symbolId, Integer limit) {
        return new RelationRequest(project, symbolId, limit == null ? DEFAULT_SYMBOL_LIMIT : limit);
    }

    static SymbolContextRequest symbolContextDefaults(
            String project, String query, String qualifiedName, String kind, String module,
            Integer depth, Integer contextLines, Integer maxTokens, boolean includeSource
    ) {
        return new SymbolContextRequest(
                project, query, qualifiedName, kind, module,
                depth == null ? DEFAULT_SEARCH_DEPTH : depth,
                contextLines == null ? DEFAULT_CONTEXT_LINES : contextLines,
                maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens,
                includeSource);
    }

    static ImpactRequest impactDefaults(String project, String symbolId, Integer depth, Integer limit) {
        return new ImpactRequest(project, symbolId,
                depth == null ? DEFAULT_IMPACT_DEPTH : depth,
                limit == null ? DEFAULT_IMPACT_LIMIT : limit);
    }
}
