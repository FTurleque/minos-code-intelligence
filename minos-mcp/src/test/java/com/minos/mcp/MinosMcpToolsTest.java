package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        assertEquals(MinosMcpTools.TOOL_COUNT, specs.size());
        assertEquals(MinosMcpTools.TOOL_COUNT,
                specs.stream().map(spec -> spec.tool().name()).distinct().count());
        assertTrue(specs.stream().allMatch(spec -> spec.tool().name().startsWith("minos_")));
        assertTrue(specs.stream().anyMatch(spec -> "minos_architecture_graph".equals(spec.tool().name())));

        assertSuccess(call(specs, "minos_project_structure", Map.of("project", "demo")));
        assertSuccess(call(specs, "minos_index_status", Map.of("project", "demo")));

        assertSuccess(call(specs, "minos_search_code", Map.ofEntries(
                Map.entry("project", "demo"),
                Map.entry("query", "Greeting"),
                Map.entry("qualifiedName", "demo.Greeting"),
                Map.entry("kind", "CLASS"),
                Map.entry("module", "api"),
                Map.entry("limit", 5),
                Map.entry("depth", 2),
                Map.entry("usages", 4),
                Map.entry("relationships", 6),
                Map.entry("contextLines", 3),
                Map.entry("maxTokens", 4096),
                Map.entry("includeSource", false)
        )));
        assertEquals(new MinosMcpBackend.SearchRequest(
                "demo", "Greeting", "demo.Greeting", "CLASS", "api",
                5, 2, 4, 6, 3, 4096, false), backend.searchRequest);

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
                MinosMcpBackend.RelationshipOperation.RELATED_TESTS
        ), backend.relationshipOperations);

        assertSuccess(call(specs, "minos_symbol_context", Map.ofEntries(
                Map.entry("project", "demo"),
                Map.entry("query", "Greeting"),
                Map.entry("qualifiedName", "demo.Greeting"),
                Map.entry("kind", "CLASS"),
                Map.entry("module", "api"),
                Map.entry("depth", 2),
                Map.entry("contextLines", 4),
                Map.entry("maxTokens", 2048),
                Map.entry("includeSource", false)
        )));
        assertEquals(new MinosMcpBackend.SymbolContextRequest(
                "demo", "Greeting", "demo.Greeting", "CLASS", "api", 2, 4, 2048, false),
                backend.symbolContextRequest);

        assertSuccess(call(specs, "minos_module_context", Map.of("project", "demo", "module", "api")));
        assertEquals("demo", backend.moduleProject);
        assertEquals("api", backend.moduleIdentifier);

        assertSuccess(call(specs, "minos_architecture", Map.of("project", "demo")));
        assertEquals("demo", backend.architectureProject);

        var graphResult = call(specs, "minos_architecture_graph", Map.of(
                "project", "demo",
                "module", "api",
                "format", "mermaid"
        ));
        assertSuccess(graphResult);
        assertTrue(((TextContent) graphResult.content().getFirst()).text().startsWith("flowchart LR"));
        assertEquals(new MinosMcpBackend.ArchitectureGraphRequest("demo", "api", "mermaid"),
                backend.architectureGraphRequest);

        assertSuccess(call(specs, "minos_impact", Map.of(
                "project", "demo",
                "symbolId", "sym-1",
                "depth", 5,
                "limit", 250
        )));
        assertEquals(new MinosMcpBackend.ImpactRequest("demo", "sym-1", 5, 250), backend.impactRequest);
    }

    @Test
    void architectureGraphMapsExplicitFormatWithoutCliTranslation() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        SyncToolSpecification graph = spec(tools.specifications(), "minos_architecture_graph");

        var result = graph.callHandler().apply(null, CallToolRequest.builder("minos_architecture_graph")
                .arguments(Map.of(
                        "project", "demo",
                        "module", "api",
                        "format", "mermaid"
                ))
                .build());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertTrue(((TextContent) result.content().getFirst()).text().startsWith("flowchart LR"));
        assertEquals(new MinosMcpBackend.ArchitectureGraphRequest("demo", "api", "mermaid"),
                backend.architectureGraphRequest);
    }

    @Test
    void enforcesSafetyBoundsBeforeBackendInvocation() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        SyncToolSpecification symbols = spec(tools.specifications(), "minos_find_symbols");

        var result = symbols.callHandler().apply(null, CallToolRequest.builder("minos_find_symbols")
                .arguments(Map.of("project", "demo", "query", "Greeting", "limit", 1001))
                .build());

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(((TextContent) result.content().getFirst()).text().contains("must be between 1 and 1000"));
        assertEquals(null, backend.symbolSearchRequest);
    }

    @Test
    void returnsApplicationFailuresAsRecoverableToolErrors() {
        RecordingBackend backend = new RecordingBackend();
        backend.statusFailure = new IllegalArgumentException("invalid input");
        MinosMcpTools tools = new MinosMcpTools(backend);
        SyncToolSpecification status = spec(tools.specifications(), "minos_index_status");

        var result = status.callHandler().apply(null, CallToolRequest.builder("minos_index_status")
                .arguments(Map.of("project", "missing"))
                .build());

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals("error: invalid input", ((TextContent) result.content().getFirst()).text());
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            List<SyncToolSpecification> specs,
            String name,
            Map<String, Object> arguments
    ) {
        return spec(specs, name).callHandler().apply(null, CallToolRequest.builder(name)
                .arguments(arguments)
                .build());
    }

    private static void assertSuccess(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    private static SyncToolSpecification spec(List<SyncToolSpecification> specs, String name) {
        SyncToolSpecification result = specs.stream()
                .filter(spec -> name.equals(spec.tool().name()))
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
        private RuntimeException statusFailure;

        @Override
        public String projectStructure(String project) {
            return "{}";
        }

        @Override
        public String indexStatus(String project) {
            if (statusFailure != null) {
                throw statusFailure;
            }
            return "{}";
        }

        @Override
        public String searchCode(SearchRequest request) {
            this.searchRequest = request;
            return "{}";
        }

        @Override
        public String findSymbols(SymbolSearchRequest request) {
            this.symbolSearchRequest = request;
            return "{\"count\":0,\"symbols\":[]}";
        }

        @Override
        public String findUsages(RelationRequest request) {
            this.usageRequest = request;
            return "{}";
        }

        @Override
        public String findRelationships(RelationshipOperation operation, RelationRequest request) {
            relationshipOperations.add(operation);
            return "{}";
        }

        @Override
        public String symbolContext(SymbolContextRequest request) {
            this.symbolContextRequest = request;
            return "{}";
        }

        @Override
        public String moduleContext(String project, String module) {
            this.moduleProject = project;
            this.moduleIdentifier = module;
            return "{}";
        }

        @Override
        public String architecture(String project) {
            this.architectureProject = project;
            return "{}";
        }

        @Override
        public String architectureGraph(ArchitectureGraphRequest request) {
            this.architectureGraphRequest = request;
            return "flowchart LR\n  m0 --> m1\n";
        }

        @Override
        public String impact(ImpactRequest request) {
            this.impactRequest = request;
            return "{}";
        }
    }
}
