package com.minos.mcp;

/** JSON Schema catalogue for the public MINOS MCP tools. */
final class McpToolSchemas {
    private McpToolSchemas() {
    }

    static String projectSchema() {
        return objectSchema(projectProperty(), "\"project\"");
    }

    static String moduleSchema() {
        return objectSchema(projectProperty() + "," + moduleProperty(), "\"project\",\"module\"");
    }

    static String architectureGraphSchema() {
        return objectSchema(
                projectProperty() + "," + moduleProperty() + "," +
                        "\"format\":{\"type\":\"string\",\"enum\":[\"json\",\"mermaid\",\"dot\"]}",
                "\"project\"");
    }

    static String relationSchema() {
        return objectSchema(
                projectProperty() + "," + symbolIdProperty() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"symbolId\"");
    }

    static String symbolSearchSchema() {
        return objectSchema(
                projectProperty() + "," + queryProperty() + "," + commonSymbolProperties() +
                        ",\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"query\"");
    }

    static String searchSchema() {
        return objectSchema(
                projectProperty() + "," + queryProperty() + "," + commonSymbolProperties() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}," +
                        "\"usages\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"relationships\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"contextLines\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"maxTokens\":{\"type\":\"integer\",\"minimum\":256,\"maximum\":32768}," +
                        "\"includeSource\":{\"type\":\"boolean\"}",
                "\"project\",\"query\"");
    }

    static String symbolContextSchema() {
        return objectSchema(
                projectProperty() + "," + queryProperty() + "," + commonSymbolProperties() + "," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}," +
                        "\"contextLines\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":50}," +
                        "\"maxTokens\":{\"type\":\"integer\",\"minimum\":256,\"maximum\":32768}," +
                        "\"includeSource\":{\"type\":\"boolean\"}",
                "\"project\",\"query\"");
    }

    static String impactSchema() {
        return objectSchema(
                projectProperty() + "," + symbolIdProperty() + "," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":32}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000}",
                "\"project\",\"symbolId\"");
    }

    static String programGraphSchema() {
        return objectSchema(
                projectProperty() + "," +
                        "\"maxNodes\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000}," +
                        "\"maxEdges\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":50000}",
                "\"project\"");
    }

    static String securitySchema() {
        return objectSchema(
                projectProperty() + "," + sourceNodeIdProperty() + "," +
                        "\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":32}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\"");
    }

    static String semanticSearchSchema() {
        return objectSchema(
                projectProperty() + "," + queryProperty() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}," +
                        "\"minimumScore\":{\"type\":\"number\",\"minimum\":-1,\"maximum\":1}",
                "\"project\",\"query\"");
    }

    static String hybridSearchSchema() {
        return objectSchema(
                projectProperty() + "," + queryProperty() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}," +
                        "\"minimumScore\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}",
                "\"project\",\"query\"");
    }

    static String hybridContextSchema() {
        return objectSchema(
                projectProperty() + "," + queryProperty() + "," +
                        "\"maxDocuments\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}," +
                        "\"maxTokens\":{\"type\":\"integer\",\"minimum\":128,\"maximum\":65536}," +
                        "\"maxTokensPerDocument\":{\"type\":\"integer\",\"minimum\":32,\"maximum\":65536}",
                "\"project\",\"query\"");
    }

    static String runtimeSessionsSchema() {
        return objectSchema(
                projectProperty() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":128}",
                "\"project\"");
    }

    static String runtimeReportSchema() {
        return objectSchema(
                projectProperty() + "," + sessionIdProperty() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\"");
    }

    static String runtimeSymbolSchema() {
        return objectSchema(
                projectProperty() + "," + symbolIdProperty() + "," + sessionIdProperty() + "," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}",
                "\"project\",\"symbolId\"");
    }

    static String emptySchema() {
        return objectSchema("", "");
    }

    static String teamWorkspaceSchema() {
        return objectSchema(
                stringProperty("workspaceId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES, "\"format\":\"uuid\""),
                "\"workspaceId\"");
    }

    static String teamAuditSchema() {
        return objectSchema("\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000}", "");
    }

    private static String commonSymbolProperties() {
        return stringProperty("qualifiedName", McpArgumentBounds.QUALIFIED_NAME_MAX_UTF8_BYTES) + "," +
                stringProperty("kind", McpArgumentBounds.KIND_MAX_UTF8_BYTES) + "," +
                moduleProperty();
    }

    private static String projectProperty() {
        return stringProperty("project", McpArgumentBounds.PROJECT_REFERENCE_MAX_UTF8_BYTES);
    }

    private static String queryProperty() {
        return stringProperty("query", McpArgumentBounds.QUERY_MAX_UTF8_BYTES);
    }

    private static String symbolIdProperty() {
        return stringProperty("symbolId", McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES);
    }

    private static String sourceNodeIdProperty() {
        return stringProperty("sourceNodeId", McpArgumentBounds.SCIP_SYMBOL_ID_MAX_UTF8_BYTES);
    }

    private static String sessionIdProperty() {
        return stringProperty("sessionId", McpArgumentBounds.HANDLE_MAX_UTF8_BYTES);
    }

    private static String moduleProperty() {
        return stringProperty("module", McpArgumentBounds.MODULE_NAME_MAX_UTF8_BYTES);
    }

    private static String stringProperty(String name, int maxUtf8Bytes) {
        return stringProperty(name, maxUtf8Bytes, null);
    }

    private static String stringProperty(String name, int maxUtf8Bytes, String extraKeywords) {
        return "\"" + name + "\":{\"type\":\"string\","
                + (extraKeywords == null || extraKeywords.isBlank() ? "" : extraKeywords + ",")
                + "\"minLength\":1,"
                + "\"maxLength\":" + McpArgumentBounds.schemaMaxCharacters(maxUtf8Bytes) + ","
                + "\"description\":\"UTF-8 text; the server accepts at most " + maxUtf8Bytes
                + " UTF-8 bytes, which is fewer characters than maxLength when the value is not ASCII.\"}";
    }

    private static String objectSchema(String properties, String required) {
        return "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
                "\"type\":\"object\",\"properties\":{" + properties + "}," +
                (required.isEmpty() ? "" : "\"required\":[" + required + "],") +
                "\"additionalProperties\":false}";
    }
}
