package com.minos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial coverage for the semantic {@code maxLength} bounds MCP tool string arguments enforce.
 *
 * <p>Every assertion calls the tool's {@code callHandler} directly with a raw arguments map,
 * bypassing whatever JSON Schema the client might have honored. That proves the server-side check
 * in {@link MinosMcpTools}, not the advertised schema, is what actually protects application code
 * from a client that ignores or was never shown the schema.</p>
 */
class MinosMcpArgumentBoundsTest {

    @Test
    void projectReferenceAcceptsExactlyTheEstablishedResolutionBoundaryAndRejectsOneByteOver() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();
        int limit = McpArgumentBounds.PROJECT_REFERENCE_MAX_UTF8_BYTES;

        String atLimit = "a".repeat(limit);
        assertSuccess(call(specs, "minos_index_status", Map.of("project", atLimit)));

        String overLimit = "a".repeat(limit + 1);
        CallToolResult rejected = call(specs, "minos_index_status", Map.of("project", overLimit));
        assertError(rejected, "project", limit);
    }

    @Test
    void queryAcceptsExactBoundaryWithMultiByteUtf8AndRejectsOneCharacterOver() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();
        int limit = McpArgumentBounds.QUERY_MAX_UTF8_BYTES;
        assertEquals(0, limit % 2, "boundary construction below assumes an even byte limit");

        // 'é' encodes to 2 UTF-8 bytes; limit/2 repetitions lands exactly on the byte boundary while
        // using far fewer *characters* than the limit, proving the check counts bytes, not chars.
        String atLimit = "é".repeat(limit / 2);
        assertEquals(limit, atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertSuccess(call(specs, "minos_search_code", Map.of("project", "demo", "query", atLimit)));

        String overLimit = "é".repeat(limit / 2 + 1);
        CallToolResult rejected = call(specs, "minos_search_code", Map.of("project", "demo", "query", overLimit));
        assertError(rejected, "query", limit);
    }

    @Test
    void supplementaryPlaneSurrogatePairsAreCountedAsFourUtf8BytesAtTheKindBoundary() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();
        int limit = McpArgumentBounds.KIND_MAX_UTF8_BYTES;
        assertEquals(0, limit % 4, "boundary construction below assumes a byte limit divisible by 4");

        // U+1F600 GRINNING FACE: one supplementary-plane code point, a high/low surrogate pair in
        // UTF-16 (2 chars), and 4 bytes in UTF-8.
        String emoji = "😀";
        String atLimit = emoji.repeat(limit / 4);
        assertEquals(limit, atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(limit / 2, atLimit.length(), "UTF-16 char count is half the UTF-8 byte count here");
        assertSuccess(call(specs, "minos_search_code", Map.of("project", "demo", "query", "q", "kind", atLimit)));

        String overLimit = emoji.repeat(limit / 4 + 1);
        CallToolResult rejected = call(specs, "minos_search_code",
                Map.of("project", "demo", "query", "q", "kind", overLimit));
        assertError(rejected, "kind", limit);
    }

    @Test
    void moduleQualifiedNameSymbolIdAndHandleFieldsEnforceTheirOwnBoundaries() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();

        assertError(call(specs, "minos_search_code", Map.of(
                        "project", "demo", "query", "q", "module", "m".repeat(McpArgumentBounds.MODULE_NAME_MAX_UTF8_BYTES + 1))),
                "module", McpArgumentBounds.MODULE_NAME_MAX_UTF8_BYTES);

        assertError(call(specs, "minos_search_code", Map.of(
                        "project", "demo", "query", "q",
                        "qualifiedName", "q".repeat(McpArgumentBounds.QUALIFIED_NAME_MAX_UTF8_BYTES + 1))),
                "qualifiedName", McpArgumentBounds.QUALIFIED_NAME_MAX_UTF8_BYTES);

        assertError(call(specs, "minos_find_usages", Map.of(
                        "project", "demo", "symbolId", "s".repeat(McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES + 1))),
                "symbolId", McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES);

        assertError(call(specs, "minos_team_workspace", Map.of(
                        "workspaceId", "w".repeat(McpArgumentBounds.HANDLE_MAX_UTF8_BYTES + 1))),
                "workspaceId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES);

        assertError(call(specs, "minos_runtime_report", Map.of(
                        "project", "demo", "sessionId", "s".repeat(McpArgumentBounds.HANDLE_MAX_UTF8_BYTES + 1))),
                "sessionId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES);

        assertError(call(specs, "minos_security_paths", Map.of(
                        "project", "demo",
                        "sourceNodeId", "n".repeat(McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES + 1))),
                "sourceNodeId", McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES);
    }

    @Test
    void oversizedFormatIsRejectedBeforeReachingTheEnumMembershipCheck() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();
        int limit = McpArgumentBounds.SMALL_TOKEN_MAX_UTF8_BYTES;

        CallToolResult rejected = call(specs, "minos_architecture_graph",
                Map.of("project", "demo", "format", "j".repeat(limit + 1)));
        assertError(rejected, "format", limit);
    }

    @Test
    void emptyRequiredStringIsRejectedAsMissingNotAsExceedingTheLengthBound() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();

        CallToolResult rejected = call(specs, "minos_index_status", Map.of("project", ""));
        assertTrue(Boolean.TRUE.equals(rejected.isError()));
        String text = text(rejected);
        assertTrue(text.contains("missing required MCP argument"), text);
        assertFalse(text.contains("exceeds UTF-8 byte limit"), text);
    }

    @Test
    void anOversizedValueIsNeverEchoedBackInThePublicErrorMessage() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();
        String marker = "UNIQUE-SENTINEL-VALUE-MUST-NOT-LEAK";
        String overLimit = marker + "x".repeat(McpArgumentBounds.QUERY_MAX_UTF8_BYTES);

        CallToolResult rejected = call(specs, "minos_search_code", Map.of("project", "demo", "query", overLimit));
        String text = text(rejected);
        assertTrue(Boolean.TRUE.equals(rejected.isError()));
        assertFalse(text.contains(marker), text);
        assertTrue(text.length() < 200, "public error text must stay small: " + text.length());
    }

    @Test
    void schemasAdvertiseMaxLengthMatchingTheServerSideBounds() {
        RecordingBackend backend = new RecordingBackend();
        MinosMcpTools tools = new MinosMcpTools(backend);
        List<SyncToolSpecification> specs = tools.specifications();

        String projectSchema = spec(specs, "minos_index_status").tool().inputSchema().toString();
        assertTrue(projectSchema.contains("maxLength"), projectSchema);

        String searchSchema = spec(specs, "minos_search_code").tool().inputSchema().toString();
        assertTrue(searchSchema.contains("maxLength"), searchSchema);
    }

    @Test
    void globalResultBudgetStillRejectsOversizedResultsAfterInputBoundsWereAdded() {
        RecordingBackend backend = new RecordingBackend();
        backend.statusResult = "x".repeat(9);
        MinosMcpTools tools = new MinosMcpTools(backend, 8);
        List<SyncToolSpecification> specs = tools.specifications();

        CallToolResult result = call(specs, "minos_index_status", Map.of("project", "demo"));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals("error: MCP_RESULT_BUDGET_EXCEEDED; reduce limits or paginate the request", text(result));
    }

    private static void assertError(CallToolResult result, String field, int limit) {
        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = text(result);
        assertTrue(text.contains(field), text);
        assertTrue(text.contains(Integer.toString(limit)), text);
        assertTrue(text.contains("exceeds UTF-8 byte limit"), text);
    }

    private static void assertSuccess(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static CallToolResult call(List<SyncToolSpecification> specs, String name, Map<String, Object> arguments) {
        return spec(specs, name).callHandler().apply(null,
                CallToolRequest.builder(name).arguments(arguments).build());
    }

    private static SyncToolSpecification spec(List<SyncToolSpecification> specs, String name) {
        return specs.stream().filter(spec -> name.equals(spec.tool().name())).findFirst().orElseThrow();
    }

    private static final class RecordingBackend implements MinosMcpBackend {
        private String statusResult = "{}";

        @Override public String projectStructure(String project) { return "{}"; }
        @Override public String indexStatus(String project) { return statusResult; }
        @Override public String searchCode(SearchRequest request) { return "{}"; }
        @Override public String findSymbols(SymbolSearchRequest request) { return "{\"count\":0,\"symbols\":[]}"; }
        @Override public String findUsages(RelationRequest request) { return "{}"; }
        @Override public String findRelationships(RelationshipOperation operation, RelationRequest request) { return "{}"; }
        @Override public String symbolContext(SymbolContextRequest request) { return "{}"; }
        @Override public String moduleContext(String project, String module) { return "{}"; }
        @Override public String architecture(String project) { return "{}"; }
        @Override public String architectureGraph(ArchitectureGraphRequest request) { return "flowchart LR\n  m0 --> m1\n"; }
        @Override public String impact(ImpactRequest request) { return "{}"; }
        @Override public String programGraph(ProgramGraphRequest request) { return "{}"; }
        @Override public String impactV2(ImpactRequest request) { return "{}"; }
        @Override public String securityPaths(SecurityRequest request) { return "{}"; }
        @Override public String semanticIndexStatus(String project) { return "{}"; }
        @Override public String semanticSearch(SemanticSearchRequest request) { return "{}"; }
        @Override public String hybridSearch(HybridSearchRequest request) { return "{}"; }
        @Override public String hybridContext(HybridContextRequest request) { return "{}"; }
        @Override public String runtimeSessions(RuntimeSessionsRequest request) { return "{}"; }
        @Override public String runtimeReport(RuntimeReportRequest request) { return "{}"; }
        @Override public String runtimeSymbol(RuntimeSymbolRequest request) { return "{}"; }
        @Override public String teamTenant() { return "{}"; }
        @Override public String teamWorkspaces() { return "{}"; }
        @Override public String teamWorkspace(String workspaceId) { return "{}"; }
        @Override public String teamMembers() { return "{}"; }
        @Override public String teamAudit(int limit) { return "{}"; }
    }
}
