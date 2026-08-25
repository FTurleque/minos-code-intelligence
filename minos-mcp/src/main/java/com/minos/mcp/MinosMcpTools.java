package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.output.DeterministicJson;
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

import static com.minos.mcp.McpToolSchemas.architectureGraphSchema;
import static com.minos.mcp.McpToolSchemas.emptySchema;
import static com.minos.mcp.McpToolSchemas.hybridContextSchema;
import static com.minos.mcp.McpToolSchemas.hybridSearchSchema;
import static com.minos.mcp.McpToolSchemas.impactSchema;
import static com.minos.mcp.McpToolSchemas.moduleSchema;
import static com.minos.mcp.McpToolSchemas.programGraphSchema;
import static com.minos.mcp.McpToolSchemas.projectSchema;
import static com.minos.mcp.McpToolSchemas.relationSchema;
import static com.minos.mcp.McpToolSchemas.runtimeReportSchema;
import static com.minos.mcp.McpToolSchemas.runtimeSessionsSchema;
import static com.minos.mcp.McpToolSchemas.runtimeSymbolSchema;
import static com.minos.mcp.McpToolSchemas.searchSchema;
import static com.minos.mcp.McpToolSchemas.securitySchema;
import static com.minos.mcp.McpToolSchemas.semanticSearchSchema;
import static com.minos.mcp.McpToolSchemas.symbolContextSchema;
import static com.minos.mcp.McpToolSchemas.symbolSearchSchema;
import static com.minos.mcp.McpToolSchemas.teamAuditSchema;
import static com.minos.mcp.McpToolSchemas.teamWorkspaceSchema;

/** MCP catalogue mapping protocol arguments directly to shared application services. */
public final class MinosMcpTools implements AutoCloseable {

    public static final int TOOL_COUNT = 31;
    public static final int MAX_RESULT_BYTES = 8 * 1024 * 1024;
    private static final Set<String> ARCHITECTURE_GRAPH_FORMATS = Set.of("json", "mermaid", "dot");
    private static final String GENERIC_TOOL_ERROR = "error: MINOS tool execution failed";
    private static final String RESULT_BUDGET_ERROR =
            "error: MCP_RESULT_BUDGET_EXCEEDED; reduce limits or paginate the request";
    private static final String ARG_LIMIT = "limit";
    private static final String ARG_MODULE = "module";
    private static final int MAX_CLIENT_ERROR_CHARS = 240;
    private static final System.Logger LOGGER = System.getLogger(MinosMcpTools.class.getName());

    private final MinosMcpBackend backend;
    private final MinosApplication ownedApplication;
    private final int maxResultBytes;

    public MinosMcpTools(Path home) {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        try {
            this.ownedApplication = MinosApplication.open(normalizedHome);
            this.backend = new MinosApplicationMcpBackend(this.ownedApplication);
            this.maxResultBytes = MAX_RESULT_BYTES;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    MinosMcpTools(MinosMcpBackend backend) {
        this(backend, MAX_RESULT_BYTES);
    }

    MinosMcpTools(MinosMcpBackend backend, int maxResultBytes) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.ownedApplication = null;
        if (maxResultBytes < 1) throw new IllegalArgumentException("maxResultBytes must be positive");
        this.maxResultBytes = maxResultBytes;
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("minos_project_structure", "Inspect a registered MINOS project, its detected languages/builds and current index snapshot.", projectSchema(), args ->
                        backend.projectStructure(requiredProject(args))),
                tool("minos_index_status", "Read the active index status and factual last-success metadata known by MINOS.", projectSchema(), args ->
                        backend.indexStatus(requiredProject(args))),
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
                        backend.moduleContext(requiredProject(args),
                                required(args, ARG_MODULE, McpArgumentBounds.MODULE_NAME_MAX_UTF8_BYTES))),
                tool("minos_architecture", "Read the composed M6 architecture intelligence view for a project, including explicit module dependency edges.", projectSchema(), args ->
                        backend.architecture(requiredProject(args))),
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
                        backend.semanticIndexStatus(requiredProject(args))),
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
                        backend.teamWorkspace(required(args, "workspaceId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES))),
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

    private CallToolResult execute(String toolName, ToolInvocation invocation, Map<String, Object> arguments) {
        try {
            String text = invocation.execute(arguments);
            String result = text == null ? "" : text.stripTrailing();
            if (exceedsUtf8Budget(result.isEmpty() ? "{}" : result, maxResultBytes)) {
                return toolError(RESULT_BUDGET_ERROR);
            }
            return CallToolResult.builder()
                    .content(List.of(TextContent.builder(result.isEmpty() ? "{}" : result).build()))
                    .build();
        } catch (DeterministicJson.OutputBudgetExceededException exception) {
            return toolError(RESULT_BUDGET_ERROR);
        } catch (IllegalArgumentException exception) {
            return toolError(clientError(toolName, exception));
        } catch (Exception exception) {
            logInternalFailure(toolName, exception);
            return toolError(GENERIC_TOOL_ERROR);
        }
    }

    private static boolean exceedsUtf8Budget(String value, long maximumBytes) {
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int encoded;
            if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                encoded = 4;
                index++;
            } else if (Character.isSurrogate(current)) {
                encoded = 1;
            } else {
                encoded = current <= 0x7f ? 1 : current <= 0x7ff ? 2 : 3;
            }
            bytes += encoded;
            if (bytes > maximumBytes) return true;
        }
        return false;
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
        if (PublicErrorMessages.looksSensitive(detail)) {
            logInternalFailure(toolName, exception);
            return GENERIC_TOOL_ERROR;
        }
        return detail.startsWith("error:") ? detail : "error: " + detail;
    }

    private static void logInternalFailure(String toolName, Exception exception) {
        LOGGER.log(System.Logger.Level.WARNING,
                "MCP tool execution failed (tool=" + toolName + ", type=" + exception.getClass().getName() + ")");
    }

    private static MinosMcpBackend.SearchRequest searchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.searchDefaults(
                requiredProject(args), requiredQuery(args), optionalQualifiedName(args),
                optionalKind(args), optionalModule(args), optionalInteger(args, ARG_LIMIT, 1, 20),
                optionalInteger(args, "depth", 0, 3), optionalInteger(args, "usages", 0, 50),
                optionalInteger(args, "relationships", 0, 50), optionalInteger(args, "contextLines", 0, 50),
                optionalInteger(args, "maxTokens", 256, 32768), optionalBoolean(args, "includeSource", true));
    }

    private static MinosMcpBackend.SymbolSearchRequest symbolSearchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.symbolDefaults(
                requiredProject(args), requiredQuery(args), optionalQualifiedName(args),
                optionalKind(args), optionalModule(args), optionalInteger(args, ARG_LIMIT, 1, 1000));
    }

    private static MinosMcpBackend.RelationRequest relationRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.relationDefaults(
                requiredProject(args), requiredSymbolId(args), optionalInteger(args, ARG_LIMIT, 1, 1000));
    }

    private static MinosMcpBackend.SymbolContextRequest symbolContextRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.symbolContextDefaults(
                requiredProject(args), requiredQuery(args), optionalQualifiedName(args),
                optionalKind(args), optionalModule(args), optionalInteger(args, "depth", 0, 3),
                optionalInteger(args, "contextLines", 0, 50), optionalInteger(args, "maxTokens", 256, 32768),
                optionalBoolean(args, "includeSource", true));
    }

    private static MinosMcpBackend.ArchitectureGraphRequest architectureGraphRequest(Map<String, Object> args) {
        String format = stringValue(args.get("format"), "json", "format", McpArgumentBounds.SMALL_TOKEN_MAX_UTF8_BYTES)
                .toLowerCase(Locale.ROOT);
        if (!ARCHITECTURE_GRAPH_FORMATS.contains(format)) {
            throw new IllegalArgumentException("unsupported architecture graph format: " + format);
        }
        return new MinosMcpBackend.ArchitectureGraphRequest(requiredProject(args), optionalModule(args), format);
    }

    private static MinosMcpBackend.ImpactRequest impactRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.impactDefaults(
                requiredProject(args), requiredSymbolId(args),
                optionalInteger(args, "depth", 1, 32), optionalInteger(args, ARG_LIMIT, 1, 10000));
    }

    private static MinosMcpBackend.ProgramGraphRequest programGraphRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.programGraphDefaults(
                requiredProject(args), optionalInteger(args, "maxNodes", 1, 10000),
                optionalInteger(args, "maxEdges", 1, 50000));
    }

    private static MinosMcpBackend.SecurityRequest securityRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.securityDefaults(
                requiredProject(args), optionalString(args, "sourceNodeId", McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES),
                optionalInteger(args, "depth", 1, 32), optionalInteger(args, ARG_LIMIT, 1, 1000));
    }

    private static MinosMcpBackend.SemanticSearchRequest semanticSearchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.semanticDefaults(
                requiredProject(args), requiredQuery(args), optionalInteger(args, ARG_LIMIT, 1, 1000),
                optionalDouble(args, "minimumScore", -1.0, 1.0));
    }

    private static MinosMcpBackend.HybridSearchRequest hybridSearchRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.hybridDefaults(
                requiredProject(args), requiredQuery(args), optionalInteger(args, ARG_LIMIT, 1, 500),
                optionalDouble(args, "minimumScore", 0.0, 1.0));
    }

    private static MinosMcpBackend.HybridContextRequest hybridContextRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.hybridContextDefaults(
                requiredProject(args), requiredQuery(args),
                optionalInteger(args, "maxDocuments", 1, 100), optionalInteger(args, "maxTokens", 128, 65536),
                optionalInteger(args, "maxTokensPerDocument", 32, 65536));
    }

    private static MinosMcpBackend.RuntimeSessionsRequest runtimeSessionsRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.runtimeSessionsDefaults(
                requiredProject(args), optionalInteger(args, ARG_LIMIT, 1, 128));
    }

    private static MinosMcpBackend.RuntimeReportRequest runtimeReportRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.runtimeReportDefaults(
                requiredProject(args), optionalString(args, "sessionId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES),
                optionalInteger(args, ARG_LIMIT, 1, 1000));
    }

    private static MinosMcpBackend.RuntimeSymbolRequest runtimeSymbolRequest(Map<String, Object> args) {
        return MinosApplicationMcpBackend.runtimeSymbolDefaults(
                requiredProject(args), requiredSymbolId(args),
                optionalString(args, "sessionId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES),
                optionalInteger(args, ARG_LIMIT, 1, 1000));
    }

    private static String requiredProject(Map<String, Object> args) {
        return required(args, "project", McpArgumentBounds.PROJECT_REFERENCE_MAX_UTF8_BYTES);
    }

    private static String requiredQuery(Map<String, Object> args) {
        return required(args, "query", McpArgumentBounds.QUERY_MAX_UTF8_BYTES);
    }

    private static String requiredSymbolId(Map<String, Object> args) {
        return required(args, "symbolId", McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES);
    }

    private static String optionalQualifiedName(Map<String, Object> args) {
        return optionalString(args, "qualifiedName", McpArgumentBounds.QUALIFIED_NAME_MAX_UTF8_BYTES);
    }

    private static String optionalKind(Map<String, Object> args) {
        return optionalString(args, "kind", McpArgumentBounds.KIND_MAX_UTF8_BYTES);
    }

    private static String optionalModule(Map<String, Object> args) {
        return optionalString(args, ARG_MODULE, McpArgumentBounds.MODULE_NAME_MAX_UTF8_BYTES);
    }

    private static String required(Map<String, Object> args, String key, int maxUtf8Bytes) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing required MCP argument: " + key);
        }
        return bounded(key, text, maxUtf8Bytes);
    }

    private static String optionalString(Map<String, Object> args, String key, int maxUtf8Bytes) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP argument " + key + " must be a non-blank string");
        }
        return bounded(key, text, maxUtf8Bytes);
    }

    /**
     * Never trusts the JSON Schema {@code maxLength} advertised to clients: a non-compliant or
     * hostile client can send a payload that ignores it entirely, so every semantically bounded
     * string argument is re-checked here in actual UTF-8 bytes before it reaches application code.
     */
    private static String bounded(String key, String text, int maxUtf8Bytes) {
        if (exceedsUtf8Budget(text, maxUtf8Bytes)) {
            throw new IllegalArgumentException(
                    "MCP argument " + key + " exceeds UTF-8 byte limit: " + maxUtf8Bytes);
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

    private static String stringValue(Object value, String defaultValue, String key, int maxUtf8Bytes) {
        if (value == null) return defaultValue;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP argument " + key + " must be a non-blank string");
        }
        return bounded(key, text, maxUtf8Bytes);
    }

    private static Map<String, Object> arguments(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : arguments;
    }

    private static int teamAuditLimit(Map<String, Object> args) {
        Integer value = optionalInteger(args, ARG_LIMIT, 1, 10_000);
        return value == null ? 200 : value;
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
