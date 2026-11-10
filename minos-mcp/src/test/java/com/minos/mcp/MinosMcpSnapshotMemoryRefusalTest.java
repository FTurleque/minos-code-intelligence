package com.minos.mcp;

import com.minos.diagnostics.SnapshotMemoryExhaustedException;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-15 — a snapshot view refused for lack of memory reaches the MCP client with its public message, not as the
 * generic tool error: the client learns that the query was refused and which setting to raise.
 */
class MinosMcpSnapshotMemoryRefusalTest {

    private static final String MESSAGE = SnapshotMemoryExhaustedException.MESSAGE_PREFIX
            + ": its view needs an estimated 600 bytes and the view memory budget is 500 bytes (50 percent of the"
            + " 1000-byte maximum heap); raise the maximum heap (-Xmx or -XX:MaxRAMPercentage) or MINOS_VIEW_HEAP_PERCENT";

    @Test
    void aMemoryRefusalIsRenderedWithItsMessage() {
        var result = McpToolCalls.call(new MinosMcpTools(failingBackend(new SnapshotMemoryExhaustedException(MESSAGE))),
                "minos_index_status", Map.of("project", "demo"));

        assertTrue(McpToolCalls.isError(result));
        assertEquals("error: " + MESSAGE, McpToolCalls.text(result));
    }

    @Test
    void aMemoryRefusalWrappedInAnUncheckedExceptionIsRenderedToo() {
        var failure = new UncheckedIOException(new SnapshotMemoryExhaustedException(MESSAGE));
        var result = McpToolCalls.call(new MinosMcpTools(failingBackend(failure)),
                "minos_find_symbols", Map.of("project", "demo", "query", "Symbol"));

        assertTrue(McpToolCalls.isError(result));
        assertTrue(((TextContent) result.content().getFirst()).text()
                .startsWith("error: insufficient memory to load this snapshot"), McpToolCalls.text(result));
    }

    private static MinosMcpBackend failingBackend(Throwable failure) {
        return (MinosMcpBackend) Proxy.newProxyInstance(
                MinosMcpBackend.class.getClassLoader(),
                new Class<?>[]{MinosMcpBackend.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "memory-refusing-mcp-backend";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    throw failure;
                });
    }
}
