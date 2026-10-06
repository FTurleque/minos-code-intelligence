package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.Map;

/** Appel d'un outil MCP par son nom, sans transport : ce que fait le serveur pour chaque requête. */
final class McpToolCalls {
    private McpToolCalls() {
    }

    static CallToolResult call(MinosMcpTools tools, String name, Map<String, Object> arguments) {
        SyncToolSpecification specification = tools.specifications().stream()
                .filter(candidate -> name.equals(candidate.tool().name()))
                .findFirst()
                .orElseThrow();
        return specification.callHandler().apply(null, CallToolRequest.builder(name).arguments(arguments).build());
    }

    static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    static boolean isError(CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }
}
