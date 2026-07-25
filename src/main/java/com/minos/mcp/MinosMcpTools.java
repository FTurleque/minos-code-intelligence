package com.minos.mcp;

import com.minos.cli.MinosLauncher;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Catalogue M10 des outils MCP MINOS. Les handlers ne portent aucune logique
 * d'analyse : ils traduisent les arguments MCP vers la surface CLI.
 */
public final class MinosMcpTools {

    public static final int TOOL_COUNT = 16;
    private static final Set<String> ARCHITECTURE_GRAPH_FORMATS = Set.of("json", "mermaid", "dot");

    private final CommandExecutor executor;

    public MinosMcpTools(Path home) {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.executor = arguments -> {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            try {
                int exitCode = MinosLauncher.run(
                        normalizedHome,
                        arguments.toArray(String[]::new),
                        output,
                        error
                );
                return new CommandResult(exitCode, output.toString(), error.toString());
            } catch (java.io.IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
    }

    MinosMcpTools(CommandExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("minos_project_structure", "Inspect a registered MINOS project, its detected languages/builds and current index snapshot.", projectSchema(), args ->
                        command("inspect", required(args, "project"))),
                tool("minos_index_status", "Read the active index status and factual last-success metadata known by MINOS.", projectSchema(), args ->
                        command("index-status", required(args, "project"))),
                tool("minos_search_code", "Build bounded compact code context from normalized MINOS knowledge.", searchSchema(), this::searchCommand),
                tool("minos_find_symbols", "Find normalized symbols in the active project snapshot.", symbolSearchSchema(), this::findSymbolsCommand),
                tool("minos_find_usages", "Find resolved usages of a normalized symbol.", relationSchema(), args -> relationCommand("find-usages", args)),
                tool("minos_find_implementations", "Find implementations of a normalized symbol.", relationSchema(), args -> relationCommand("find-implementations", args)),
                tool("minos_find_callers", "Find incoming CALLS relations when the provider exposes them.", relationSchema(), args -> relationCommand("find-callers", args)),
                tool("minos_find_callees", "Find outgoing CALLS relations when the provider exposes them.", relationSchema(), args -> relationCommand("find-callees", args)),
                tool("minos_dependencies", "Find outgoing derived DEPENDS_ON relations.", relationSchema(), args -> relationCommand("dependencies", args)),
                tool("minos_dependents", "Find incoming derived DEPENDS_ON relations.", relationSchema(), args -> relationCommand("dependents", args)),
                tool("minos_related_tests", "Find tests related to a production symbol with MINOS evidence and confidence.", relationSchema(), args -> relationCommand("related-tests", args)),
                tool("minos_symbol_context", "Build one-root compact context for a symbol query, including bounded usages, relationships and relevant source.", symbolContextSchema(), this::symbolContextCommand),
                tool("minos_module_context", "Read the compact M6 architecture context for one module.", moduleSchema(), args ->
                        command("architecture", required(args, "project"), "--module", required(args, "module"))),
                tool("minos_architecture", "Read the composed M6 architecture intelligence view for a project, including explicit module dependency edges.", projectSchema(), args ->
                        command("architecture", required(args, "project"))),
                tool("minos_architecture_graph", "Render the observed inter-module dependency graph as JSON, Mermaid or Graphviz DOT; optionally focus on one module and its direct neighbours.", architectureGraphSchema(), this::architectureGraphCommand),
                tool("minos_impact", "Estimate direct and indirect potential impact with deterministic explanatory paths and explicit limitations.", impactSchema(), this::impactCommand)
        );
    }

    private SyncToolSpecification tool(
            String name,
            String description,
            String schema,
            Function<Map<String, Object>, List<String>> commandFactory
    ) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder(name, McpJsonDefaults.getMapper(), schema)
                        .description(description)
                        .build())
                .callHandler((exchange, request) -> execute(commandFactory.apply(arguments(request.arguments()))))
                .build();
    }

    private CallToolResult execute(List<String> command) {
        CommandResult result = executor.execute(command);
        if (result.exitCode() == 0) {
            String text = result.stdout().stripTrailing();
            return CallToolResult.builder()
                    .content(List.of(TextContent.builder(text.isEmpty() ? "{}" : text).build()))
                    .build();
        }
        String message = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return CallToolResult.builder()
                .content(List.of(TextContent.builder(message.strip()).build()))
                .isError(true)
                .build();
    }

    private List<String> searchCommand(Map<String, Object> args) {
        List<String> command = command("search", required(args, "project"), required(args, "query"));
        option(command, args, "qualifiedName", "--qualified-name");
        option(command, args, "kind", "--kind");
        option(command, args, "module", "--module");
        option(command, args, "limit", "--limit");
        option(command, args, "depth", "--depth");
        option(command, args, "usages", "--usages");
        option(command, args, "relationships", "--relationships");
        option(command, args, "contextLines", "--context-lines");
        option(command, args, "maxTokens", "--max-tokens");
        if (Boolean.FALSE.equals(args.get("includeSource"))) {
            command.add("--no-source");
        }
        return command;
    }

    private List<String> findSymbolsCommand(Map<String, Object> args) {
        List<String> command = command("find-symbol", required(args, "project"), required(args, "query"));
        option(command, args, "qualifiedName", "--qualified-name");
        option(command, args, "kind", "--kind");
        option(command, args, "module", "--module");
        option(command, args, "limit", "--limit");
        return command;
    }

    private List<String> relationCommand(String operation, Map<String, Object> args) {
        List<String> command = command(operation, required(args, "project"), required(args, "symbolId"));
        option(command, args, "limit", "--limit");
        return command;
    }

    private List<String> symbolContextCommand(Map<String, Object> args) {
        List<String> command = command("search", required(args, "project"), required(args, "query"));
        command.addAll(List.of("--limit", "1"));
        option(command, args, "qualifiedName", "--qualified-name");
        option(command, args, "kind", "--kind");
        option(command, args, "module", "--module");
        option(command, args, "depth", "--depth");
        option(command, args, "maxTokens", "--max-tokens");
        option(command, args, "contextLines", "--context-lines");
        if (Boolean.FALSE.equals(args.get("includeSource"))) {
            command.add("--no-source");
        }
        return command;
    }

    private List<String> architectureGraphCommand(Map<String, Object> args) {
        List<String> command = new ArrayList<>();
        command.add("architecture");
        command.add(required(args, "project"));
        option(command, args, "module", "--module");
        String format = stringValue(args.get("format"), "json").toLowerCase(Locale.ROOT);
        if (!ARCHITECTURE_GRAPH_FORMATS.contains(format)) {
            throw new IllegalArgumentException("unsupported architecture graph format: " + format);
        }
        command.add("--format");
        command.add(format);
        return command;
    }

    private List<String> impactCommand(Map<String, Object> args) {
        List<String> command = command("impact", required(args, "project"), required(args, "symbolId"));
        option(command, args, "depth", "--depth");
        option(command, args, "limit", "--limit");
        return command;
    }

    private static List<String> command(String name, String... operands) {
        List<String> values = new ArrayList<>();
        values.add(name);
        values.addAll(List.of(operands));
        values.addAll(List.of("--format", "json"));
        return values;
    }

    private static void option(List<String> command, Map<String, Object> args, String key, String cliOption) {
        Object value = args.get(key);
        if (value != null) {
            command.add(cliOption);
            command.add(String.valueOf(value));
        }
    }

    private static String required(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing required MCP argument: " + key);
        }
        return text;
    }

    private static String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP argument must be a non-blank string");
        }
        return text;
    }

    private static Map<String, Object> arguments(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : arguments;
    }

    private static String projectSchema() {
        return objectSchema("\"project\":{\"type\":\"string\",\"minLength\":1}", "\"project\"");
    }

    private static String moduleSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1},\"module\":{\"type\":\"string\",\"minLength\":1}",
                "\"project\",\"module\"");
    }

    private static String architectureGraphSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"module\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"format\":{\"type\":\"string\",\"enum\":[\"json\",\"mermaid\",\"dot\"]}",
                "\"project\"");
    }

    private static String relationSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"symbolId\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"symbolId\"");
    }

    private static String symbolSearchSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," +
                        commonSymbolProperties() +
                        ",\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"query\"");
    }

    private static String searchSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," +
                        commonSymbolProperties() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}," +
                        "\"usages\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"relationships\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"contextLines\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"maxTokens\":{\"type\":\"integer\",\"minimum\":256,\"maximum\":32768}," +
                        "\"includeSource\":{\"type\":\"boolean\"}",
                "\"project\",\"query\"");
    }

    private static String symbolContextSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," +
                        commonSymbolProperties() + "," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}," +
                        "\"contextLines\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"maxTokens\":{\"type\":\"integer\",\"minimum\":256,\"maximum\":32768}," +
                        "\"includeSource\":{\"type\":\"boolean\"}",
                "\"project\",\"query\"");
    }

    private static String impactSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"symbolId\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":32}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000}",
                "\"project\",\"symbolId\"");
    }

    private static String commonSymbolProperties() {
        return "\"qualifiedName\":{\"type\":\"string\",\"minLength\":1}," +
                "\"kind\":{\"type\":\"string\",\"minLength\":1}," +
                "\"module\":{\"type\":\"string\",\"minLength\":1}";
    }

    private static String objectSchema(String properties, String required) {
        return "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
                "\"type\":\"object\",\"properties\":{" + properties + "}," +
                "\"required\":[" + required + "],\"additionalProperties\":false}";
    }

    @FunctionalInterface
    interface CommandExecutor {
        CommandResult execute(List<String> arguments);
    }

    record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
