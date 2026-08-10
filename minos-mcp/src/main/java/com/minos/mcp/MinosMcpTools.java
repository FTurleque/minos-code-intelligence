package com.minos.mcp;

import com.minos.application.MinosApplication;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** MCP catalogue mapping protocol arguments directly to shared application services. */
public final class MinosMcpTools implements AutoCloseable {

    public static final int TOOL_COUNT = 31;
    private static final Set<String> ARCHITECTURE_GRAPH_FORMATS = Set.of("json", "mermaid", "dot");
    private static final String GENERIC_TOOL_ERROR = "error: MINOS tool execution failed";
    private static final int MAX_CLIENT_ERROR_CHARS = 240;
    private static final System.Logger LOGGER = System.getLogger(MinosMcpTools.class.getName());

    private final MinosMcpBackend backend;
    private final MinosApplication ownedApplication;

    public MinosMcpTools(Path home) {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        try {
            this.ownedApplication = MinosApplication.open(normalizedHome);
            this.backend = new MinosApplicationMcpBackend(this.ownedApplication);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    MinosMcpTools(MinosMcpBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.ownedApplication = null;
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("minos_project_structure", "Inspect a registered MINOS project, its detected languages/builds and current index snapshot.", projectSchema(), args ->
                        backend.projectStructure(required(args, "project"))),
                tool("minos_index_status", "Read the active index status and factual last-success metadata known by MINOS.", projectSchema(), args ->
                        backend.indexStatus(required(args, "project"))),
                tool("minos_search_code", "Build bounded compact code context from normalized MINOS knowledge.", searchSchema(), args ->
                        backend.searchCode(searchRequest(args))),
                tool("minos_find_symbols", "Find normalized symbols in the active project snapshot.", symbolSearchSchema(), args ->
                        backend.findSymbols(symbolSearchRequest(args))),
                tool("minos_find_usages", "Find resolved usages of a normalized symbol.", relationSchema(), args ->
                        backend.findUsages(relationRequest(args))),
                tool("minos_find_implementations", "Find implementations of a normalized symbol.", relationSchema(), args ->
                        backend.findRelationships(MinosMcpBackend.RelationshipOperation.IMPLEMENTATIONS, relationRequest(args))),
                tool("minos_find_callers", "Find incoming CALLS relations when the provider exposes them.", relationSchema(), args ->
                        backend.findRelationships(MinosMcpBackend.RelationshipOperation.CALLERS, relationRequest(args))),
                tool("minos_find_callees", "Find outgoing CALLS relations when the provider exposes them.", relationSchema(), args ->
                        backend.findRelationships(MinosMcpBackend.RelationshipOperation.CALLEES, relationRequest(args))),
                tool("minos_dependencies", "Find outgoing derived DEPENDS_ON relations.", relationSchema(), args ->
                        backend.findRelationships(MinosMcpBackend.RelationshipOperation.DEPENDENCIES, relationRequest(args))),
                tool("minos_dependents", "Find incoming derived DEPENDS_ON relations.", relationSchema(), args ->
                        backend.findRelationships(MinosMcpBackend.RelationshipOperation.DEPENDENTS, relationRequest(args))),
                tool("minos_related_tests", "Find tests related to a production symbol with MINOS evidence and confidence.", relationSchema(), args ->
                        backend.findRelationships(MinosMcpBackend.RelationshipOperation.RELATED_TESTS, relationRequest(args))),
                tool("minos_symbol_context", "Build one-root compact context for a symbol query, including bounded usages, relationships and relevant source.", symbolContextSchema(), args ->
                        backend.symbolContext(symbolContextRequest(args))),
                tool("minos_module_context", "Read the compact M6 architecture context for one module.", moduleSchema(), args ->
                        backend.moduleContext(required(args, "project"), required(args, "module"))),
                tool("minos_architecture", "Read the composed M6 architecture intelligence view for a project, including explicit module dependency edges.", projectSchema(), args ->
                        backend.architecture(required(args, "project"))),
                tool("minos_architecture_graph", "Render the observed inter-module dependency graph as JSON, Mermaid or Graphviz DOT; optionally focus on one module and its direct neighbours.", architectureGraphSchema(), args ->
                        backend.architectureGraph(architectureGraphRequest(args))),
                tool("minos_impact", "Estimate direct and indirect potential impact with deterministic explanatory paths and explicit limitations.", impactSchema(), args ->
                        backend.impact(impactRequest(args))),
                tool("minos_program_graph", "Read the bounded M19 program graph with explicit capabilities, nature, provenance and limitations.", programGraphSchema(), args ->
                        backend.programGraph(programGraphRequest(args))),
                tool("minos_impact_v2", "Enrich M8 impact with bounded call/data-flow paths while reporting baseline and advanced additions separately.", impactSchema(), args ->
                        backend.impactV2(impactRequest(args))),
                tool("minos_security_paths", "Find bounded observed source-to-sink paths and sanitizers; never claims exhaustive vulnerability presence or absence.", securitySchema(), args ->
                        backend.securityPaths(securityRequest(args))),
                tool("minos_semantic_index_status", "Read optional M20 semantic index state, active snapshot alignment, model identity, size and limitations.", projectSchema(), args ->
                        backend.semanticIndexStatus(required(args, "project"))),
                tool("minos_semantic_search", "Search the ready semantic index by intent. Vector scores are heuristic ranking signals, never structural code facts.", semanticSearchSchema(), args ->
                        backend.semanticSearch(semanticSearchRequest(args))),
                tool("minos_hybrid_search", "Fuse structured lexical/graph signals with optional semantic ranking while exposing every signal and its nature.", hybridSearchSchema(), args ->
                        backend.hybridSearch(hybridSearchRequest(args))),
                tool("minos_hybrid_context", "Build bounded M20 hybrid context under explicit document and token budgets.", hybridContextSchema(), args ->
                        backend.hybridContext(hybridContextRequest(args))),
                tool("minos_runtime_sessions", "List immutable partial runtime observation sessions and their active-snapshot alignment.", runtimeSessionsSchema(), args ->
                        backend.runtimeSessions(runtimeSessionsRequest(args))),
                tool("minos_runtime_report", "Read observed runtime coverage ratios, hot paths and calls; absence never proves non-execution.", runtimeReportSchema(), args ->
                        backend.runtimeReport(runtimeReportRequest(args))),
                tool("minos_runtime_symbol", "Read partial runtime observations for one static symbol without claiming exhaustive execution.", runtimeSymbolSchema(), args ->
                        backend.runtimeSymbol(runtimeSymbolRequest(args))),
                tool("minos_team_tenant", "Read the authenticated tenant summary. Authentication comes only from MINOS_TEAM_TOKEN.", emptySchema(), args ->
                        backend.teamTenant()),
                tool("minos_team_workspaces", "List tenant-isolated shared workspaces without exposing credentials.", emptySchema(), args ->
                        backend.teamWorkspaces()),
                tool("minos_team_workspace", "Read one workspace and its exact project/snapshot bindings in the authenticated tenant.", teamWorkspaceSchema(), args ->
                        backend.teamWorkspace(required(args, "workspaceId"))),
                tool("minos_team_members", "List tenant members, roles and permissions for the authenticated tenant.", emptySchema(), args ->
                        backend.teamMembers()),
                tool("minos_team_audit", "Read a bounded newest-first view of the authenticated tenant audit chain.", teamAuditSchema(), args ->
                        backend.teamAudit(teamAuditLimit(args)))
        );
    }

    private SyncToolSpecification tool(String name, String description, String schema, ToolInvocation invocation) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder(name, McpJsonDefaults.getMapper(), schema).description(description).build())
                .callHandler((exchange, request) -> execute(name, invocation, arguments(request.arguments())))
                .build();
    }

    private static CallToolResult execute(String toolName, ToolInvocation invocation, Map<String, Object> arguments) {
        try {
            String text = invocation.execute(arguments);
            String result = text == null ? "" : text.stripTrailing();
            return CallToolResult.builder()
                    .content(List.of(TextContent.builder(result.isEmpty() ? "{}" : result).build()))
                    .build();
        } catch (IllegalArgumentException exception) {
            return toolError(clientError(toolName, exception));
        } catch (Exception exception) {
            logInternalFailure(toolName, exception);
            return toolError(GENERIC_TOOL_ERROR);
        }
    }

    private static CallToolResult toolError(String message) {
        return CallToolResult.builder()
                .content(List.of(TextContent.builder(message).build()))
                .isError(true)
                .build();
    }

    private static String clientError(String toolName, IllegalArgumentException exception) {
        if (exception.getCause() != null) {
            logInternalFailure(toolName, exception);
            return GENERIC_TOOL_ERROR;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "error: invalid MCP argument";
        String detail = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (detail.length() > MAX_CLIENT_ERROR_CHARS) {
            detail = detail.substring(0, MAX_CLIENT_ERROR_CHARS);
        }
        if (looksSensitive(detail)) {
            logInternalFailure(toolName, exception);
            return GENERIC_TOOL_ERROR;
        }
        return detail.startsWith("error:") ? detail : "error: " + detail;
    }

    private static boolean looksSensitive(String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("jdbc:") || lower.contains("password") || lower.contains("secret")
                || lower.contains("bearer") || lower.contains("authorization") || lower.contains("token=")) {
            return true;
        }
        return containsAbsolutePath(detail);
    }

    private static boolean containsAbsolutePath(String detail) {
        for (int index = 0; index < detail.length(); index++) {
            if (index > 0 && !Character.isWhitespace(detail.charAt(index - 1))) continue;
            char first = detail.charAt(index);
            if (first == '/') return true;
            if (index + 2 < detail.length()
                    && Character.isLetter(first)
                    && detail.charAt(index + 1) == ':'
                    && (detail.charAt(index + 2) == '\\' || detail.charAt(index + 2) == '/')) {
                return true;
            }
        }
        return false;
    }

    private static void logInternalFailure(String toolName, Exception exception) {
        LOGGER.log(System.Logger.Level.WARNING,
                "MCP tool execution failed (tool=" + toolName + ", type=" + exception.getClass().getName() + ")");
    }

    private static MinosMcpBackend.SearchRequest searchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.searchDefaults(
                required(args, "project"), required(args, "query"), optionalString(args, "qualifiedName"),
                optionalString(args, "kind"), optionalString(args, "module"), optionalInteger(args, "limit", 1, 20),
                optionalInteger(args, "depth", 0, 3), optionalInteger(args, "usages", 0, 50),
                optionalInteger(args, "relationships", 0, 50), optionalInteger(args, "contextLines", 0, 50),
                optionalInteger(args, "maxTokens", 256, 32768), optionalBoolean(args, "includeSource", true));
    }

    private static MinosMcpBackend.SymbolSearchRequest symbolSearchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.symbolDefaults(
                required(args, "project"), required(args, "query"), optionalString(args, "qualifiedName"),
                optionalString(args, "kind"), optionalString(args, "module"), optionalInteger(args, "limit", 1, 1000));
    }

    private static MinosMcpBackend.RelationRequest relationRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.relationDefaults(
                required(args, "project"), required(args, "symbolId"), optionalInteger(args, "limit", 1, 1000));
    }

    private static MinosMcpBackend.SymbolContextRequest symbolContextRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.symbolContextDefaults(
                required(args, "project"), required(args, "query"), optionalString(args, "qualifiedName"),
                optionalString(args, "kind"), optionalString(args, "module"), optionalInteger(args, "depth", 0, 3),
                optionalInteger(args, "contextLines", 0, 50), optionalInteger(args, "maxTokens", 256, 32768),
                optionalBoolean(args, "includeSource", true));
    }

    private static MinosMcpBackend.ArchitectureGraphRequest architectureGraphRequest(Map<String, Object> args) {
        String format = stringValue(args.get("format"), "json").toLowerCase(Locale.ROOT);
        if (!ARCHITECTURE_GRAPH_FORMATS.contains(format)) {
            throw new IllegalArgumentException("unsupported architecture graph format: " + format);
        }
        return new MinosMcpBackend.ArchitectureGraphRequest(required(args, "project"), optionalString(args, "module"), format);
    }

    private static MinosMcpBackend.ImpactRequest impactRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.impactDefaults(
                required(args, "project"), required(args, "symbolId"),
                optionalInteger(args, "depth", 1, 32), optionalInteger(args, "limit", 1, 10000));
    }

    private static MinosMcpBackend.ProgramGraphRequest programGraphRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.programGraphDefaults(
                required(args, "project"), optionalInteger(args, "maxNodes", 1, 100000),
                optionalInteger(args, "maxEdges", 1, 500000));
    }

    private static MinosMcpBackend.SecurityRequest securityRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.securityDefaults(
                required(args, "project"), optionalString(args, "sourceNodeId"),
                optionalInteger(args, "depth", 1, 32), optionalInteger(args, "limit", 1, 1000));
    }

    private static MinosMcpBackend.SemanticSearchRequest semanticSearchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.semanticDefaults(
                required(args, "project"), required(args, "query"), optionalInteger(args, "limit", 1, 1000),
                optionalDouble(args, "minimumScore", -1.0, 1.0));
    }

    private static MinosMcpBackend.HybridSearchRequest hybridSearchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.hybridDefaults(
                required(args, "project"), required(args, "query"), optionalInteger(args, "limit", 1, 500),
                optionalDouble(args, "minimumScore", 0.0, 1.0));
    }

    private static MinosMcpBackend.HybridContextRequest hybridContextRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.hybridContextDefaults(
                required(args, "project"), required(args, "query"),
                optionalInteger(args, "maxDocuments", 1, 100), optionalInteger(args, "maxTokens", 128, 65536),
                optionalInteger(args, "maxTokensPerDocument", 32, 65536));
    }

    private static MinosMcpBackend.RuntimeSessionsRequest runtimeSessionsRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.runtimeSessionsDefaults(
                required(args, "project"), optionalInteger(args, "limit", 1, 128));
    }

    private static MinosMcpBackend.RuntimeReportRequest runtimeReportRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.runtimeReportDefaults(
                required(args, "project"), optionalString(args, "sessionId"),
                optionalInteger(args, "limit", 1, 1000));
    }

    private static MinosMcpBackend.RuntimeSymbolRequest runtimeSymbolRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.runtimeSymbolDefaults(
                required(args, "project"), required(args, "symbolId"), optionalString(args, "sessionId"),
                optionalInteger(args, "limit", 1, 1000));
    }

    private static String required(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing required MCP argument: " + key);
        }
        return text;
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP argument " + key + " must be a non-blank string");
        }
        return text;
    }

    private static Integer optionalInteger(Map<String, Object> args, String key, int minimum, int maximum) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("MCP argument " + key + " must be an integer");
        double raw = number.doubleValue();
        int parsed = number.intValue();
        if (!Double.isFinite(raw) || raw != parsed) throw new IllegalArgumentException("MCP argument " + key + " must be an integer");
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("MCP argument " + key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static Double optionalDouble(Map<String, Object> args, String key, double minimum, double maximum) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("MCP argument " + key + " must be numeric");
        double parsed = number.doubleValue();
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("MCP argument " + key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static boolean optionalBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("MCP argument " + key + " must be a boolean");
        return flag;
    }

    private static String stringValue(Object value, String defaultValue) {
        if (value == null) return defaultValue;
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
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," + commonSymbolProperties() +
                        ",\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"query\"");
    }

    private static String searchSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," + commonSymbolProperties() + "," +
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
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," + commonSymbolProperties() + "," +
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

    private static String programGraphSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"maxNodes\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000}," +
                        "\"maxEdges\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500000}",
                "\"project\"");
    }

    private static String securitySchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"sourceNodeId\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":32}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\"");
    }

    private static String semanticSearchSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}," +
                        "\"minimumScore\":{\"type\":\"number\",\"minimum\":-1,\"maximum\":1}",
                "\"project\",\"query\"");
    }

    private static String hybridSearchSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}," +
                        "\"minimumScore\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}",
                "\"project\",\"query\"");
    }

    private static String hybridContextSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"query\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"maxDocuments\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}," +
                        "\"maxTokens\":{\"type\":\"integer\",\"minimum\":128,\"maximum\":65536}," +
                        "\"maxTokensPerDocument\":{\"type\":\"integer\",\"minimum\":32,\"maximum\":65536}",
                "\"project\",\"query\"");
    }

    private static String runtimeSessionsSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":128}",
                "\"project\"");
    }

    private static String runtimeReportSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"sessionId\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\"");
    }

    private static String runtimeSymbolSchema() {
        return objectSchema(
                "\"project\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"symbolId\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"sessionId\":{\"type\":\"string\",\"minLength\":1}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"symbolId\"");
    }

    private static int teamAuditLimit(Map<String, Object> args) {
        Integer value = optionalInteger(args, "limit", 1, 10_000);
        return value == null ? 200 : value;
    }

    private static String emptySchema() {
        return objectSchema("", "");
    }

    private static String teamWorkspaceSchema() {
        return objectSchema("\"workspaceId\":{\"type\":\"string\",\"format\":\"uuid\"}", "\"workspaceId\"");
    }

    private static String teamAuditSchema() {
        return objectSchema("\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000}", "");
    }

    private static String commonSymbolProperties() {
        return "\"qualifiedName\":{\"type\":\"string\",\"minLength\":1}," +
                "\"kind\":{\"type\":\"string\",\"minLength\":1}," +
                "\"module\":{\"type\":\"string\",\"minLength\":1}";
    }

    private static String objectSchema(String properties, String required) {
        return "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
                "\"type\":\"object\",\"properties\":{" + properties + "}," +
                (required.isEmpty() ? "" : "\"required\":[" + required + "],") +
                "\"additionalProperties\":false}";
    }

    @FunctionalInterface
    private interface ToolInvocation {
        String execute(Map<String, Object> arguments) throws Exception;
    }


    @Override
    public void close() throws IOException {
        if (ownedApplication != null) ownedApplication.close();
    }
}
