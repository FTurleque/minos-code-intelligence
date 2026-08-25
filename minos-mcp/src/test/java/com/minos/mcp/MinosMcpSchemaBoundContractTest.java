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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advertised protocol constraint and the enforced security budget are two different things and
 * must stay consistent in the one direction that matters.
 *
 * <p>JSON Schema counts {@code maxLength} in characters; MINOS enforces its budgets in UTF-8 bytes.
 * Publishing the byte number under the character keyword was only accidentally right for ASCII. The
 * invariant the schema must satisfy is <em>completeness</em>: every value the server accepts also
 * satisfies the advertised {@code maxLength}, so a compliant client is never told "too long" about
 * something MINOS would have taken. The reverse implication deliberately does not hold, and the
 * tests below pin that down as an explicit, documented property rather than an accident.</p>
 */
class MinosMcpSchemaBoundContractTest {

    @Test
    void schemaMaxLengthIsTheTightestCharacterBoundABudgetImplies() {
        assertEquals(McpArgumentBounds.QUERY_MAX_UTF8_BYTES,
                McpArgumentBounds.schemaMaxCharacters(McpArgumentBounds.QUERY_MAX_UTF8_BYTES));
        assertThrows(IllegalArgumentException.class, () -> McpArgumentBounds.schemaMaxCharacters(0));
    }

    /**
     * Applies to every tool, not to one hand-picked schema: any bounded string argument MINOS
     * publishes must either be an exhaustive enum or carry both a character bound and the byte
     * budget that actually decides the request.
     */
    @Test
    void everyAdvertisedStringPropertyStatesItsByteBudgetNextToItsCharacterBound() {
        for (SyncToolSpecification specification : tools()) {
            String tool = specification.tool().name();
            Map<String, Object> properties = propertiesOf(tool);
            if (properties == null) {
                continue;
            }
            properties.forEach((name, raw) -> {
                Map<String, Object> property = asMap(raw);
                if (!"string".equals(property.get("type")) || property.containsKey("enum")) {
                    return;
                }
                Object maxLength = property.get("maxLength");
                assertTrue(maxLength instanceof Number,
                        () -> tool + "." + name + " advertises no character bound");
                Object description = property.get("description");
                assertTrue(description instanceof String text && text.contains("UTF-8 bytes"),
                        () -> tool + "." + name + " does not state the authoritative byte budget: "
                                + description);
                assertTrue(((String) description).contains(maxLength + " UTF-8 bytes"),
                        () -> tool + "." + name + " byte budget and character bound disagree: " + description);
            });
        }
    }

    @Test
    void anAsciiValueAtTheExactBoundaryIsBothSchemaValidAndServerAccepted() {
        int limit = McpArgumentBounds.QUERY_MAX_UTF8_BYTES;
        String value = "a".repeat(limit);

        assertEquals(limit, value.codePointCount(0, value.length()));
        assertEquals(limit, value.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(value.codePointCount(0, value.length()) <= advertisedMaxLength("minos_search_code", "query"));
        assertSuccess(call("minos_search_code", Map.of("project", "demo", "query", value)));
    }

    @Test
    void anAsciiValueOneCharacterOverIsRejectedBySchemaAndServerAlike() {
        int limit = McpArgumentBounds.QUERY_MAX_UTF8_BYTES;
        String value = "a".repeat(limit + 1);

        assertTrue(value.codePointCount(0, value.length()) > advertisedMaxLength("minos_search_code", "query"));
        assertError(call("minos_search_code", Map.of("project", "demo", "query", value)));
    }

    /**
     * The documented asymmetry: 4096 {@code é} characters satisfy {@code maxLength} but are 8192
     * UTF-8 bytes, so the server -- which is the authority -- refuses them.
     */
    @Test
    void aMultiByteValueWithinMaxLengthIsStillRejectedByTheAuthoritativeByteBudget() {
        int limit = McpArgumentBounds.QUERY_MAX_UTF8_BYTES;
        String value = "é".repeat(limit);

        assertTrue(value.codePointCount(0, value.length()) <= advertisedMaxLength("minos_search_code", "query"),
                "the schema alone would accept this value");
        assertTrue(value.getBytes(StandardCharsets.UTF_8).length > limit);
        assertError(call("minos_search_code", Map.of("project", "demo", "query", value)));
    }

    @Test
    void supplementaryPlaneCharactersAreCountedAsCodePointsNotUtf16Units() {
        int limit = McpArgumentBounds.KIND_MAX_UTF8_BYTES;
        String value = "😀".repeat(limit / 4);

        assertEquals(limit / 4, value.codePointCount(0, value.length()),
                "JSON Schema counts characters, so a surrogate pair is one, not two");
        assertEquals(limit / 2, value.length(), "Java string length is UTF-16 units, deliberately different");
        assertEquals(limit, value.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(value.codePointCount(0, value.length()) <= advertisedMaxLength("minos_search_code", "kind"));
        assertSuccess(call("minos_search_code", Map.of("project", "demo", "query", "q", "kind", value)));
    }

    /**
     * Completeness, stated as the property itself: whatever the server accepts, the schema also
     * accepts. This is what breaks if {@code maxLength} is ever tightened below the byte budget.
     */
    @Test
    void everyServerAcceptedValueAlsoSatisfiesTheAdvertisedMaxLength() {
        int limit = McpArgumentBounds.QUERY_MAX_UTF8_BYTES;
        int advertised = advertisedMaxLength("minos_search_code", "query");
        List<String> acceptedAtTheByteBoundary = List.of(
                "a".repeat(limit),
                "é".repeat(limit / 2),
                "😀".repeat(limit / 4),
                "a".repeat(limit - 4) + "😀");

        for (String value : acceptedAtTheByteBoundary) {
            assertEquals(limit, value.getBytes(StandardCharsets.UTF_8).length, value.length() + " units");
            assertSuccess(call("minos_search_code", Map.of("project", "demo", "query", value)));
            assertTrue(value.codePointCount(0, value.length()) <= advertised,
                    "a server-accepted value must never violate the advertised maxLength");
        }
    }

    @Test
    void theServerCheckRemainsAuthoritativeForAClientThatIgnoresTheSchemaEntirely() {
        String value = "a".repeat(McpArgumentBounds.PROJECT_REFERENCE_MAX_UTF8_BYTES + 1);

        CallToolResult result = call("minos_index_status", Map.of("project", value));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(text(result).contains("exceeds UTF-8 byte limit"), text(result));
        assertFalse(text(result).contains(value), "the oversized value must never be echoed back");
    }

    /** The maxLength advertised for one specific property, read from the published schema itself. */
    private static int advertisedMaxLength(String tool, String property) {
        Object raw = propertiesOf(tool).get(property);
        assertTrue(raw != null, "no property " + property + " advertised by " + tool);
        Object maxLength = asMap(raw).get("maxLength");
        assertTrue(maxLength instanceof Number, "no maxLength advertised for " + tool + "." + property);
        return ((Number) maxLength).intValue();
    }

    /** The published input schema, as the SDK hands it to a client: a plain JSON object tree. */
    private static Map<String, Object> propertiesOf(String tool) {
        return asMap(asMap(spec(tool).tool().inputSchema()).get("properties"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private static List<SyncToolSpecification> tools() {
        return new MinosMcpTools(new SchemaProbeBackend()).specifications();
    }

    private static SyncToolSpecification spec(String name) {
        return tools().stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("unknown MCP tool: " + name));
    }

    private static CallToolResult call(String name, Map<String, Object> arguments) {
        return spec(name).callHandler().apply(null, CallToolRequest.builder(name).arguments(arguments).build());
    }

    private static void assertSuccess(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result));
    }

    private static void assertError(CallToolResult result) {
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(text(result).contains("exceeds UTF-8 byte limit"), text(result));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static final class SchemaProbeBackend implements MinosMcpBackend {

        @Override public String projectStructure(String project) { return "{}"; }
        @Override public String indexStatus(String project) { return "{}"; }
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
