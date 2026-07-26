package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosMcpToolsTest {

    @Test
    void exposesStableUniqueToolCatalogAndMapsFindSymbolsToTypedRequest() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);

        List<SyncToolSpecification> specs = tools.specifications();
        assertEquals(MinosMcpTools.TOOL_COUNT, specs.size());
        assertEquals(MinosMcpTools.TOOL_COUNT,
                specs.stream().map(spec -> spec.tool().name()).distinct().count());
        assertTrue(specs.stream().allMatch(spec -> spec.tool().name().startsWith("minos_")));
        assertTrue(specs.stream().anyMatch(spec -> "minos_architecture_graph".equals(spec.tool().name())));

        SyncToolSpecification symbols = spec(specs, "minos_find_symbols");
        var result = symbols.callHandler().apply(null, CallToolRequest.builder("minos_find_symbols")
                .arguments(Map.of("project", "demo", "query", "Greeting", "limit", 7))
                .build());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals("{\"count\":0,\"symbols\":[]}", ((TextContent) result.content().getFirst()).text());
        assertEquals(new MinosMcpBackend.SymbolSearchRequest(
                "demo", "Greeting", null, null, null, 7), backend.symbolSearchRequest);
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

    private static SyncToolSpecification spec(List<SyncToolSpecification> specs, String name) {
        SyncToolSpecification result = specs.stream()
                .filter(spec -> name.equals(spec.tool().name()))
                .findFirst().orElseThrow();
        assertNotNull(result.callHandler());
        return result;
    }

    private static final class RecordingBackend implements MinosMcpBackend {
        private SymbolSearchRequest symbolSearchRequest;
        private ArchitectureGraphRequest architectureGraphRequest;
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
            return "{}";
        }

        @Override
        public String findSymbols(SymbolSearchRequest request) {
            this.symbolSearchRequest = request;
            return "{\"count\":0,\"symbols\":[]}";
        }

        @Override
        public String findUsages(RelationRequest request) {
            return "{}";
        }

        @Override
        public String findRelationships(RelationshipOperation operation, RelationRequest request) {
            return "{}";
        }

        @Override
        public String symbolContext(SymbolContextRequest request) {
            return "{}";
        }

        @Override
        public String moduleContext(String project, String module) {
            return "{}";
        }

        @Override
        public String architecture(String project) {
            return "{}";
        }

        @Override
        public String architectureGraph(ArchitectureGraphRequest request) {
            this.architectureGraphRequest = request;
            return "flowchart LR\n  m0 --> m1\n";
        }

        @Override
        public String impact(ImpactRequest request) {
            return "{}";
        }
    }
}
