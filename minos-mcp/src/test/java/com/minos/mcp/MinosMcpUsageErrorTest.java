package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.diagnostics.UsageException;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An argument error is reported to the client only when the validation raised a {@link UsageException}. */
class MinosMcpUsageErrorTest {

    /** One documented invalid input of a published tool and what the client is told. */
    private record Invalid(String label, String tool, Map<String, Object> arguments, String expected) {
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) map.put((String) pairs[index], pairs[index + 1]);
        return map;
    }

    private static final List<Invalid> TABLE = List.of(
            new Invalid("limit below the range", "minos_find_symbols",
                    arguments("project", "p", "query", "q", "limit", -1), "must be between 1 and 1000"),
            new Invalid("limit that is not an integer", "minos_find_symbols",
                    arguments("project", "p", "query", "q", "limit", 1.5), "must be an integer"),
            new Invalid("limit of the wrong type", "minos_find_usages",
                    arguments("project", "p", "symbolId", "s", "limit", "ten"), "must be an integer"),
            new Invalid("missing project", "minos_index_status", arguments(), "missing required MCP argument: project"),
            new Invalid("blank query", "minos_search_code", arguments("project", "p", "query", " "),
                    "missing required MCP argument: query"),
            new Invalid("unknown argument", "minos_index_status", arguments("project", "p", "limt", 1),
                    "unknown MCP argument: limt"),
            new Invalid("unknown graph format", "minos_architecture_graph",
                    arguments("project", "p", "format", "svg"), "unsupported architecture graph format: svg"),
            new Invalid("boolean of the wrong type", "minos_search_code",
                    arguments("project", "p", "query", "q", "includeSource", "yes"), "must be a boolean"),
            new Invalid("score of the wrong type", "minos_semantic_search",
                    arguments("project", "p", "query", "q", "minimumScore", "high"), "must be numeric"),
            new Invalid("score out of range", "minos_semantic_search",
                    arguments("project", "p", "query", "q", "minimumScore", 7), "must be between"),
            new Invalid("project of the wrong type", "minos_index_status", arguments("project", 12),
                    "missing required MCP argument: project"));

    @Test
    void everyDocumentedInvalidInputIsAnActionableUsageErrorAndNeverTheOpaqueError() {
        MinosMcpTools tools = new MinosMcpTools(stub(null));

        for (Invalid invalid : TABLE) {
            try (LogCapture log = new LogCapture(MinosMcpTools.class)) {
                var result = McpToolCalls.call(tools, invalid.tool(), invalid.arguments());
                String text = McpToolCalls.text(result);

                assertTrue(McpToolCalls.isError(result), invalid.label());
                assertTrue(text.startsWith("error: "), invalid.label() + ": " + text);
                assertTrue(text.contains(invalid.expected()), invalid.label() + ": " + text);
                assertFalse(text.contains("MINOS tool execution failed"), invalid.label() + ": " + text);
                assertFalse(text.contains("zzz-kind"), "the value sent for kind is not echoed: " + text);
                assertEquals(List.of(), log.warnings(), invalid.label() + " is not an internal failure");
            }
        }
    }

    /** The kind is parsed by the real backend: the answer is an argument error that does not echo the value. */
    @Test
    void anUnknownSymbolKindIsAnArgumentErrorThatNamesTheArgumentWithoutTheValue(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.open(home.resolve("app"))) {
            application.projectRegistry().registerProject(Files.createDirectories(home.resolve("project")), "demo");
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            try (LogCapture log = new LogCapture(MinosMcpTools.class)) {
                String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_find_symbols",
                        Map.of("project", "demo", "query", "q", "kind", "zzz-kind")));

                assertEquals("error: unsupported value for MCP argument kind", text);
                assertFalse(text.contains("zzz-kind"), text);
                assertEquals(List.of(), log.warnings());
            }
        }
    }

    @Test
    void aPlainIllegalArgumentExceptionOfAServiceIsAnInternalFailureNotAnArgumentError() {
        for (RuntimeException failure : List.<RuntimeException>of(
                new IllegalArgumentException("limit must be positive"),
                new NumberFormatException("For input string: \"x\""),
                new InvalidPathException("a|b", "Illegal char"),
                new PatternSyntaxException("Unclosed group", "(", 0))) {
            MinosMcpTools tools = new MinosMcpTools(stub(failure));
            try (LogCapture log = new LogCapture(MinosMcpTools.class)) {
                String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

                McpToolCalls.assertGenericToolError(text);
                assertFalse(text.contains("limit must be positive"), text);
                assertEquals(1, log.warnings().size(), log.warnings().toString());
                assertTrue(log.warnings().get(0).contains("tool=minos_index_status"), log.warnings().get(0));
                assertTrue(log.warnings().get(0).contains("type=" + failure.getClass().getName()),
                        log.warnings().get(0));
            }
        }
    }

    @Test
    void aUsageErrorOfAServiceIsReportedToTheClient() {
        MinosMcpTools tools = new MinosMcpTools(stub(new UsageException("unknown module: core")));

        String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

        assertEquals("error: unknown module: core", text);
    }

    @Test
    void aUsageErrorWhoseMessageCarriesAnAbsolutePathIsTheOpaqueError() {
        MinosMcpTools tools = new MinosMcpTools(stub(new UsageException("cannot find C:\\Users\\alice\\project")));

        String text = McpToolCalls.text(McpToolCalls.call(tools, "minos_index_status", Map.of("project", "p")));

        McpToolCalls.assertGenericToolError(text);
        assertFalse(text.contains("alice"), text);
    }

    /** A backend that answers {@code {}} to everything, or throws {@code failure} when one is given. */
    private static MinosMcpBackend stub(RuntimeException failure) {
        return (MinosMcpBackend) Proxy.newProxyInstance(
                MinosMcpBackend.class.getClassLoader(),
                new Class<?>[]{MinosMcpBackend.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "stub-mcp-backend";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    if (failure != null) throw failure;
                    return "{}";
                });
    }
}
