package com.minos.mcp;

/** Typed read-only backend consumed by MCP request mappings. */
interface MinosMcpBackend {

    String projectStructure(String project) throws Exception;

    String indexStatus(String project) throws Exception;

    String searchCode(SearchRequest request) throws Exception;

    String findSymbols(SymbolSearchRequest request) throws Exception;

    String findUsages(RelationRequest request) throws Exception;

    String findRelationships(RelationshipOperation operation, RelationRequest request) throws Exception;

    String symbolContext(SymbolContextRequest request) throws Exception;

    String moduleContext(String project, String module) throws Exception;

    String architecture(String project) throws Exception;

    String architectureGraph(ArchitectureGraphRequest request) throws Exception;

    String impact(ImpactRequest request) throws Exception;

    record SearchRequest(
            String project,
            String query,
            String qualifiedName,
            String kind,
            String module,
            int limit,
            int depth,
            int usages,
            int relationships,
            int contextLines,
            int maxTokens,
            boolean includeSource
    ) {
    }

    record SymbolSearchRequest(
            String project,
            String query,
            String qualifiedName,
            String kind,
            String module,
            int limit
    ) {
    }

    record RelationRequest(String project, String symbolId, int limit) {
    }

    enum RelationshipOperation {
        IMPLEMENTATIONS,
        CALLERS,
        CALLEES,
        DEPENDENCIES,
        DEPENDENTS,
        RELATED_TESTS
    }

    record SymbolContextRequest(
            String project,
            String query,
            String qualifiedName,
            String kind,
            String module,
            int depth,
            int contextLines,
            int maxTokens,
            boolean includeSource
    ) {
    }

    record ArchitectureGraphRequest(String project, String module, String format) {
    }

    record ImpactRequest(String project, String symbolId, int depth, int limit) {
    }
}
