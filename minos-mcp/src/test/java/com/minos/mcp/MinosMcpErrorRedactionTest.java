package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosMcpErrorRedactionTest {

    @Test
    void internalBackendFailureNeverReturnsOperationalDetails() {
        MinosMcpBackend backend = failingBackend(new IOException(
                "jdbc:postgresql://db/minos password=super-secret /home/operator/minos"));
        var result = call(new MinosMcpTools(backend), "minos_index_status", Map.of("project", "demo"));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((TextContent) result.content().getFirst()).text();
        assertEquals("error: MINOS tool execution failed", text);
        assertFalse(text.contains("super-secret"));
        assertFalse(text.contains("jdbc:"));
        assertFalse(text.contains("/home/operator"));
    }

    @Test
    void sensitiveIllegalArgumentDetailsAreAlsoRedacted() {
        MinosMcpBackend backend = failingBackend(new IllegalArgumentException(
                "invalid configuration token=super-secret at /home/operator/minos"));
        var result = call(new MinosMcpTools(backend), "minos_index_status", Map.of("project", "demo"));

        assertEquals("error: MINOS tool execution failed",
                ((TextContent) result.content().getFirst()).text());
    }

    @Test
    void boundedNonSensitiveArgumentErrorsRemainActionable() {
        MinosMcpBackend backend = failingBackend(new AssertionError("backend must not be invoked"));
        var result = call(new MinosMcpTools(backend), "minos_find_symbols",
                Map.of("project", "demo", "query", "Symbol", "limit", 1001));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals("error: MCP argument limit must be between 1 and 1000",
                ((TextContent) result.content().getFirst()).text());
    }

    private static MinosMcpBackend failingBackend(Throwable failure) {
        return (MinosMcpBackend) Proxy.newProxyInstance(
                MinosMcpBackend.class.getClassLoader(),
                new Class<?>[]{MinosMcpBackend.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "failing-mcp-backend";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    throw failure;
                });
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            MinosMcpTools tools,
            String name,
            Map<String, Object> arguments
    ) {
        SyncToolSpecification specification = tools.specifications().stream()
                .filter(candidate -> name.equals(candidate.tool().name()))
                .findFirst()
                .orElseThrow();
        return specification.callHandler().apply(null,
                CallToolRequest.builder(name).arguments(arguments).build());
    }
}
