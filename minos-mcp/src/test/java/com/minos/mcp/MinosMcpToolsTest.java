package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosMcpToolsTest {

    @Test
    void exposesStableUniqueToolCatalogAndTranslatesToJsonCli() {
        AtomicReference<List<String>> invoked = new AtomicReference<>();
        MinosMcpTools tools = new MinosMcpTools(arguments -> {
            invoked.set(arguments);
            return new MinosMcpTools.CommandResult(0, "{\"count\":0}\n", "");
        });

        List<SyncToolSpecification> specs = tools.specifications();
        assertEquals(MinosMcpTools.TOOL_COUNT, specs.size());
        assertEquals(MinosMcpTools.TOOL_COUNT,
                specs.stream().map(spec -> spec.tool().name()).distinct().count());
        assertTrue(specs.stream().allMatch(spec -> spec.tool().name().startsWith("minos_")));
        assertTrue(specs.stream().anyMatch(spec -> "minos_architecture_graph".equals(spec.tool().name())));

        SyncToolSpecification symbols = specs.stream()
                .filter(spec -> "minos_find_symbols".equals(spec.tool().name()))
                .findFirst().orElseThrow();
        var result = symbols.callHandler().apply(null, CallToolRequest.builder("minos_find_symbols")
                .arguments(Map.of("project", "demo", "query", "Greeting", "limit", 7))
                .build());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals("{\"count\":0}", ((TextContent) result.content().getFirst()).text());
        assertEquals(List.of(
                "find-symbol", "demo", "Greeting", "--format", "json", "--limit", "7"
        ), invoked.get());
    }

    @Test
    void architectureGraphSelectsExplicitGraphFormatWithoutDuplicateFormatOption() {
        AtomicReference<List<String>> invoked = new AtomicReference<>();
        MinosMcpTools tools = new MinosMcpTools(arguments -> {
            invoked.set(arguments);
            return new MinosMcpTools.CommandResult(0, "flowchart LR\n  m0 --> m1\n", "");
        });
        SyncToolSpecification graph = tools.specifications().stream()
                .filter(spec -> "minos_architecture_graph".equals(spec.tool().name()))
                .findFirst().orElseThrow();

        var result = graph.callHandler().apply(null, CallToolRequest.builder("minos_architecture_graph")
                .arguments(Map.of(
                        "project", "demo",
                        "module", "api",
                        "format", "mermaid"
                ))
                .build());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertTrue(((TextContent) result.content().getFirst()).text().startsWith("flowchart LR"));
        assertEquals(List.of(
                "architecture", "demo", "--module", "api", "--format", "mermaid"
        ), invoked.get());
    }

    @Test
    void returnsCliUsageFailuresAsRecoverableToolErrors() {
        MinosMcpTools tools = new MinosMcpTools(arguments ->
                new MinosMcpTools.CommandResult(2, "", "error: invalid input\n"));
        SyncToolSpecification status = tools.specifications().stream()
                .filter(spec -> "minos_index_status".equals(spec.tool().name()))
                .findFirst().orElseThrow();

        var result = status.callHandler().apply(null, CallToolRequest.builder("minos_index_status")
                .arguments(Map.of("project", "missing"))
                .build());

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals("error: invalid input", ((TextContent) result.content().getFirst()).text());
    }
}
