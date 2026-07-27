package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosMcpToolsTest {

    @Test
    void exposesStableUniqueToolCatalogAndMapsAllToolsToTypedRequests() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);

        List<SyncToolSpecification> specs = tools.specifications();
        assertEquals(23, MinosMcpTools.TOOL_COUNT);
        assertEquals(MinosMcpTools.TOOL_COUNT, specs.size());
        assertEquals(MinosMcpTools.TOOL_COUNT,
                specs.stream().map(spec -> spec.tool().name()).distinct().count());
        assertTrue(specs.stream().allMatch(spec -> spec.tool().name().startsWith("minos_")));
        Set<String> names = specs.stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "minos_architecture_graph",
                "minos_program_graph",
                "minos_impact_v2",
                "minos_security_paths",
                "minos_semantic_index_status",
                "minos_semantic_search",
                "minos_hybrid_search",
                "minos_hybrid_context")));

        assertSuccess(call(specs, "minos_project_structure", Map.of("project", "demo")));
        assertSuccess(call(specs, "minos_index_status", Map.of("project", "demo")));

        assertSuccess(call(specs, "minos_search_code", Map.ofEntries(
                Map.entry("project", "demo"), Map.entry("query", "Greeting"),
                Map.entry("qualifiedName", "demo.Greeting"), Map.entry("kind", "CLASS"),
                Map.entry("module", "api"), Map.entry("limit", 5), Map.entry("depth", 2),
                Map.entry("usages", 4), Map.entry("relationships", 6), Map.entry("contextLines", 3),
                Map.entry("maxTokens", 4096), Map.entry("includeSource", false))));
        assertEquals(new MinosMcpBackend.SearchRequest(
                "demo", "Greeting", "demo.Greeting", "CLASS", "api", 5, 2, 4, 6, 3, 4096, false),
                backend.searchRequest);

        var symbolsResult = call(specs, "minos_find_symbols",
                Map.of("project", "demo", "query", "Greeting", "limit", 7));
        assertSuccess(symbolsResult);
        assertEquals("{\"count\":0,\"symbols\":[]}", ((TextContent) symbolsResult.content().getFirst()).text());
        assertEquals(new MinosMcpBackend.SymbolSearchRequest(
                "demo", "Greeting", null, null, null, 7), backend.symbolSearchRequest);

        Map<String, Object> relationArguments = Map.of("project", "demo", "symbolId", "sym-1", "limit", 8);
        assertSuccess(call(specs, "minos_find_usages", relationArguments));
        assertEquals(new MinosMcpBackend.RelationRequest("demo", "sym-1", 8), backend.usageRequest);
        assertSuccess(call(specs, "minos_find_implementations", relationArguments));
        assertSuccess(call(specs, "minos_find_callers", relationArguments));
        assertSuccess(call(specs, "minos_find_callees", relationArguments));
        assertSuccess(call(specs, "minos_dependencies", relationArguments));
        assertSuccess(call(specs, "minos_dependents", relationArguments));
        assertSuccess(call(specs, "minos_related_tests", relationArguments));
        assertEquals(List.of(
                MinosMcpBackend.RelationshipOperation.IMPLEMENTATIONS,
                MinosMcpBackend.RelationshipOperation.CALLERS,
                MinosMcpBackend.RelationshipOperation.CALLEES,
                MinosMcpBackend.RelationshipOperation.DEPENDENCIES,
                MinosMcpBackend.RelationshipOperation.DEPENDENTS,
                MinosMcpBackend.RelationshipOperation.RELATED_TESTS), backend.relationshipOperations);

        assertSuccess(call(specs, "minos_symbol_context", Map.ofEntries(
                Map.entry("project", "demo"), Map.entry("query", "Greeting"),
                Map.entry("qualifiedName", "demo.Greeting"), Map.entry("kind", "CLASS"),
                Map.entry("module", "api"), Map.entry("depth", 2), Map.entry("contextLines", 4),
                Map.entry("maxTokens", 2048), Map.entry("includeSource", false))));
        assertEquals(new MinosMcpBackend.SymbolContextRequest(
                "demo", "Greeting", "demo.Greeting", "CLASS", "api", 2, 4, 2048, false),
                backend.symbolContextRequest);

        assertSuccess(call(specs, "minos_module_context", Map.of("project", "demo", "module", "api")));
        assertEquals("demo", backend.moduleProject);
        assertEquals("api", backend.moduleIdentifier);
        assertSuccess(call(specs, "minos_architecture", Map.of("project", "demo")));
        assertEquals("demo", backend.architectureProject);

        var graphResult = call(specs, "minos_architecture_graph", Map.of(
                "project", "demo", "module", "api", "format", "mermaid"));
        assertSuccess(graphResult);
        assertTrue(((TextContent) graphResult.content().getFirst()).text().startsWith("flowchart LR"));
        assertEquals(new MinosMcpBackend.ArchitectureGraphRequest("demo", "api", "mermaid"),
                backend.architectureGraphRequest);

        assertSuccess(call(specs, "minos_impact", Map.of(
                "project", "demo", "symbolId", "sym-1", "depth", 5, "limit", 250)));
        assertEquals(new MinosMcpBackend.ImpactRequest("demo", "sym-1", 5, 250), backend.impactRequest);
        assertSuccess(call(specs, "minos_program_graph", Map.of(
                "project", "demo", "maxNodes", 123, "maxEdges", 456)));
        assertEquals(new MinosMcpBackend.ProgramGraphRequest("demo", 123, 456), backend.programGraphRequest);
        assertSuccess(call(specs, "minos_impact_v2", Map.of(
                "project", "demo", "symbolId", "sym-2", "depth", 6, "limit", 300)));
        assertEquals(new MinosMcpBackend.ImpactRequest("demo", "sym-2", 6, 300), backend.impactV2Request);
        assertSuccess(call(specs, "minos_security_paths", Map.of(
                "project", "demo", "sourceNodeId", "source:1", "depth", 7, "limit", 50)));
        assertEquals(new MinosMcpBackend.SecurityRequest("demo", "source:1", 7, 50), backend.securityRequest);

        assertSuccess(call(specs, "minos_semantic_index_status", Map.of("project", "demo")));
        assertEquals("demo", backend.semanticStatusProject);
        assertSuccess(call(specs, "minos_semantic_search", Map.of(
                "project", "demo", "query", "authentication", "limit", 12, "minimumScore", -0.25)));
        assertEquals(new MinosMcpBackend.SemanticSearchRequest("demo", "authentication", 12, -0.25),
                backend.semanticSearchRequest);
        assertSuccess(call(specs, "minos_hybrid_search", Map.of(
                "project", "demo", "query", "authentication", "limit", 13, "minimumScore", 0.2)));
        assertEquals(new MinosMcpBackend.HybridSearchRequest("demo", "authentication", 13, 0.2),
                backend.hybridSearchRequest);
        assertSuccess(call(specs, "minos_hybrid_context", Map.of(
                "project", "demo", "query", "authentication", "maxDocuments", 4,
                "maxTokens", 2048, "maxTokensPerDocument", 256)));
        assertEquals(new MinosMcpBackend.HybridContextRequest(
                "demo", "authentication", 4, 2048, 256), backend.hybridContextRequest);
    }

    @Test
    void semanticToolsApplyDefaultsAndSafetyBoundsBeforeBackendInvocation() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();

        assertSuccess(call(specs, "minos_semantic_search", Map.of("project", "demo", "query", "auth")));
        assertEquals(new MinosMcpBackend.SemanticSearchRequest("demo", "auth", 20, 0.0), backend.semanticSearchRequest);
        assertSuccess(call(specs, "minos_hybrid_search", Map.of("project", "demo", "query", "auth")));
        assertEquals(new MinosMcpBackend.HybridSearchRequest("demo", "auth", 20, 0.0), backend.hybridSearchRequest);
        assertSuccess(call(specs, "minos_hybrid_context", Map.of("project", "demo", "query", "auth")));
        assertEquals(new MinosMcpBackend.HybridContextRequest("demo", "auth", 10, 4000, 800), backend.hybridContextRequest);

        backend.semanticSearchRequest = null;
        var semanticTooLarge = call(specs, "minos_semantic_search",
                Map.of("project", "demo", "query", "auth", "limit", 1001));
        assertTrue(Boolean.TRUE.equals(semanticTooLarge.isError()));
        assertEquals(null, backend.semanticSearchRequest);

        backend.hybridSearchRequest = null;
        var hybridScore = call(specs, "minos_hybrid_search",
                Map.of("project", "demo", "query", "auth", "minimumScore", 1.1));
        assertTrue(Boolean.TRUE.equals(hybridScore.isError()));
        assertEquals(null, backend.hybridSearchRequest);

        backend.hybridContextRequest = null;
        var contextTokens = call(specs, "minos_hybrid_context",
                Map.of("project", "demo", "query", "auth", "maxTokens", 127));
        assertTrue(Boolean.TRUE.equals(contextTokens.isError()));
        assertEquals(null, backend.hybridContextRequest);
    }

    @Test
    void architectureGraphMapsExplicitFormatWithoutCliTranslation() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        SyncToolSpecification graph = spec(tools.specifications(), "minos_architecture_graph");
        var result = graph.callHandler().apply(null, CallToolRequest.builder("minos_architecture_graph")
                .arguments(Map.of("project", "demo", "module", "api", "format", "mermaid"))
                .build());
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertTrue(((TextContent) result.content().getFirst()).text().startsWith("flowchart LR"));
        assertEquals(new MinosMcpBackend.ArchitectureGraphRequest("demo", "api", "mermaid"),
                backend.architectureGraphRequest);
    }

    @Test
    void enforcesHistoricalSafetyBoundsBeforeBackendInvocation() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        var symbolsResult = call(tools.specifications(), "minos_find_symbols",
                Map.of("project", "demo", "query", "Greeting", "limit", 1001));
        assertTrue(Boolean.TRUE.equals(symbolsResult.isError()));
        assertEquals(null, backend.symbolSearchRequest);
        var graphResult = call(tools.specifications(), "minos_program_graph",
                Map.of("project", "demo", "maxNodes", 100001));
        assertTrue(Boolean.TRUE.equals(graphResult.isError()));
        assertEquals(null, backend.programGraphRequest);
    }

    @Test
    void returnsApplicationFailuresAsRecoverableToolErrors() {
        RecordingBackend backend = new RecordingBackend();
        backend.statusFailure = new IllegalArgumentException("invalid input");
        MinosMcpTools tools = new MinosMcpTools(backend);
        var result = spec(tools.specifications(), "minos_index_status").callHandler().apply(null,
                CallToolRequest.builder("minos_index_status").arguments(Map.of("project", "missing")).build());
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals("error: invalid input", ((TextContent) result.content().getFirst()).text());
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            List<SyncToolSpecification> specs, String name, Map<String, Object> arguments) {
        return spec(specs, name).callHandler().apply(null,
                CallToolRequest.builder(name).arguments(arguments).build());
    }

    private static void assertSuccess(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    private static SyncToolSpecification spec(List<SyncToolSpecification> specs, String name) {
        SyncToolSpecification result = specs.stream().filter(spec -> name.equals(spec.tool().name()))
                .findFirst().orElseThrow();
        assertNotNull(result.callHandler());
        return result;
    }

    private static final class RecordingBackend implements MinosMcpBackend {
        private SearchRequest searchRequest;
        private SymbolSearchRequest symbolSearchRequest;
        private RelationRequest usageRequest;
        private final List<RelationshipOperation> relationshipOperations = new ArrayList<>();
        private SymbolContextRequest symbolContextRequest;
        private String moduleProject;
        private String moduleIdentifier;
        private String architectureProject;
        private ArchitectureGraphRequest architectureGraphRequest;
        private ImpactRequest impactRequest;
        private ProgramGraphRequest programGraphRequest;
        private ImpactRequest impactV2Request;
        private SecurityRequest securityRequest;
        private String semanticStatusProject;
        private SemanticSearchRequest semanticSearchRequest;
        private HybridSearchRequest hybridSearchRequest;
        private HybridContextRequest hybridContextRequest;
        private RuntimeException statusFailure;

        @Override public String projectStructure(String project) { return "{}"; }
        @Override public String indexStatus(String project) { if (statusFailure != null) throw statusFailure; return "{}"; }
        @Override public String searchCode(SearchRequest request) { searchRequest = request; return "{}"; }
        @Override public String findSymbols(SymbolSearchRequest request) { symbolSearchRequest = request; return "{\"count\":0,\"symbols\":[]}"; }
        @Override public String findUsages(RelationRequest request) { usageRequest = request; return "{}"; }
        @Override public String findRelationships(RelationshipOperation operation, RelationRequest request) { relationshipOperations.add(operation); return "{}"; }
        @Override public String symbolContext(SymbolContextRequest request) { symbolContextRequest = request; return "{}"; }
        @Override public String moduleContext(String project, String module) { moduleProject = project; moduleIdentifier = module; return "{}"; }
        @Override public String architecture(String project) { architectureProject = project; return "{}"; }
        @Override public String architectureGraph(ArchitectureGraphRequest request) { architectureGraphRequest = request; return "flowchart LR\n  m0 --> m1\n"; }
        @Override public String impact(ImpactRequest request) { impactRequest = request; return "{}"; }
        @Override public String programGraph(ProgramGraphRequest request) { programGraphRequest = request; return "{}"; }
        @Override public String impactV2(ImpactRequest request) { impactV2Request = request; return "{}"; }
        @Override public String securityPaths(SecurityRequest request) { securityRequest = request; return "{}"; }
        @Override public String semanticIndexStatus(String project) { semanticStatusProject = project; return "{}"; }
        @Override public String semanticSearch(SemanticSearchRequest request) { semanticSearchRequest = request; return "{}"; }
        @Override public String hybridSearch(HybridSearchRequest request) { hybridSearchRequest = request; return "{}"; }
        @Override public String hybridContext(HybridContextRequest request) { hybridContextRequest = request; return "{}"; }
    }
}
