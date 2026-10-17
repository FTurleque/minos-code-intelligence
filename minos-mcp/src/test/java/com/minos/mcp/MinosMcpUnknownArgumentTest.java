package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-SEC-09 : le schéma publié dit {@code additionalProperties:false} ; MINOS le fait respecter lui-même pour
 * chaque outil du catalogue, sans exécuter l'opération et sans reproduire de valeur ni de nom démesuré.
 */
class MinosMcpUnknownArgumentTest {

    private static final String GENERIC_ERROR = "error: MINOS tool execution failed";

    @Test
    void everyToolRefusesAnUnknownKeyWithoutRunningItsOperation() {
        AtomicInteger backendCalls = new AtomicInteger();
        MinosMcpTools tools = new MinosMcpTools(countingBackend(backendCalls));

        List<SyncToolSpecification> specifications = tools.specifications();
        assertEquals(MinosMcpTools.TOOL_COUNT, specifications.size());
        for (SyncToolSpecification specification : specifications) {
            String name = specification.tool().name();
            Map<String, Object> arguments = requiredArguments(specification);
            arguments.put("limt", 1);

            CallToolResult result = McpToolCalls.call(tools, name, arguments);

            assertTrue(McpToolCalls.isError(result), name);
            assertEquals("error: unknown MCP argument: limt", McpToolCalls.text(result), name);
        }
        assertEquals(0, backendCalls.get(), "aucune opération n'est exécutée");
    }

    @Test
    void anUnknownKeyIsRefusedEvenWhenOnlyThatKeyIsSent() {
        MinosMcpTools tools = new MinosMcpTools(countingBackend(new AtomicInteger()));

        CallToolResult result = McpToolCalls.call(tools, "minos_team_tenant", Map.of("extra", "value"));

        assertTrue(McpToolCalls.isError(result));
        assertEquals("error: unknown MCP argument: extra", McpToolCalls.text(result));
    }

    @Test
    void theRefusalNamesTheFirstUnknownKeyInNaturalOrderAndNeverItsValue() {
        MinosMcpTools tools = new MinosMcpTools(countingBackend(new AtomicInteger()));
        Map<String, Object> arguments = requiredArguments(specification(tools, "minos_impact"));
        arguments.put("zzz", "C:/Users/operator/secret.txt");
        arguments.put("aaa", "/home/operator/secret.txt");

        String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_impact", arguments));

        assertEquals("error: unknown MCP argument: aaa", text);
        assertFalse(text.contains("secret"));
    }

    @Test
    void anOversizedKeyIsBoundedInTheResponse() {
        MinosMcpTools tools = new MinosMcpTools(countingBackend(new AtomicInteger()));
        String hugeKey = "k".repeat(10_000);
        Map<String, Object> arguments = requiredArguments(specification(tools, "minos_impact"));
        arguments.put(hugeKey, 1);

        String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_impact", arguments));

        assertTrue(text.length() < 200, "longueur " + text.length());
        assertFalse(text.contains("k".repeat(100)));
    }

    @Test
    void anAbsolutePathShapedKeyYieldsTheGenericErrorWithoutTheKey() {
        MinosMcpTools tools = new MinosMcpTools(countingBackend(new AtomicInteger()));
        for (String key : List.of("C:/Users/operator/secret.txt", "/home/operator/secret.txt")) {
            Map<String, Object> arguments = requiredArguments(specification(tools, "minos_impact"));
            arguments.put(key, 1);

            String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_impact", arguments));

            assertEquals(GENERIC_ERROR, text, key);
        }
    }

    @Test
    void theSchemaArgumentsAreStillServed() {
        AtomicInteger backendCalls = new AtomicInteger();
        MinosMcpTools tools = new MinosMcpTools(countingBackend(backendCalls));

        CallToolResult result = McpToolCalls.call(tools, "minos_impact",
                Map.of("project", "demo", "symbolId", "scip-demo", "limit", 5));

        assertFalse(McpToolCalls.isError(result), McpToolCalls.text(result));
        assertEquals(1, backendCalls.get());
    }

    private static SyncToolSpecification specification(MinosMcpTools tools, String name) {
        return tools.specifications().stream()
                .filter(candidate -> name.equals(candidate.tool().name()))
                .findFirst()
                .orElseThrow();
    }

    /** Un argument chaîne pour chaque propriété requise du schéma publié. */
    private static Map<String, Object> requiredArguments(SyncToolSpecification specification) {
        Map<String, Object> arguments = new HashMap<>();
        if (specification.tool().inputSchema().get("required") instanceof List<?> required) {
            required.forEach(key -> arguments.put(String.valueOf(key), "value"));
        }
        return arguments;
    }

    private static MinosMcpBackend countingBackend(AtomicInteger calls) {
        return (MinosMcpBackend) Proxy.newProxyInstance(
                MinosMcpBackend.class.getClassLoader(),
                new Class<?>[]{MinosMcpBackend.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "counting-mcp-backend";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    calls.incrementAndGet();
                    return "{}";
                });
    }
}
