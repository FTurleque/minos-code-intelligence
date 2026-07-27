package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

/** Thin IntelliJ transport adapter for additive M19/M20 capabilities exposed by minos-ide v1. */
@Service(Service.Level.PROJECT)
public final class MinosM21Client {

    public static final String PROGRAM_GRAPH = "program-graph";
    public static final String IMPACT_V2 = "impact-v2";
    public static final String SECURITY_PATHS = "security-paths";
    public static final String SEMANTIC_INDEX_STATUS = "semantic-index-status";
    public static final String SEMANTIC_INDEX_SYNC = "semantic-index-sync";
    public static final String SEMANTIC_SEARCH = "semantic-search";
    public static final String HYBRID_SEARCH = "hybrid-search";
    public static final String HYBRID_CONTEXT = "hybrid-context";

    private final MinosCliClient client;

    public MinosM21Client(Project project) {
        this.client = MinosCliClient.getInstance(project);
    }

    public static MinosM21Client getInstance(Project project) {
        return project.getService(MinosM21Client.class);
    }

    public JsonObject programGraph(String projectId, int maxNodes, int maxEdges) throws MinosProtocolException {
        return execute(PROGRAM_GRAPH, List.of(
                "ide", PROGRAM_GRAPH, requireText(projectId, "projectId"),
                "--max-nodes", Integer.toString(maxNodes),
                "--max-edges", Integer.toString(maxEdges),
                "--format", "json"));
    }

    public JsonObject impactV2(String projectId, String symbolId) throws MinosProtocolException {
        return execute(IMPACT_V2, List.of(
                "ide", IMPACT_V2, requireText(projectId, "projectId"), requireText(symbolId, "symbolId"),
                "--format", "json"));
    }

    public JsonObject securityPaths(String projectId) throws MinosProtocolException {
        return execute(SECURITY_PATHS, List.of(
                "ide", SECURITY_PATHS, requireText(projectId, "projectId"),
                "--format", "json"));
    }

    public JsonObject semanticIndexStatus(String projectId) throws MinosProtocolException {
        return execute(SEMANTIC_INDEX_STATUS, List.of(
                "ide", SEMANTIC_INDEX_STATUS, requireText(projectId, "projectId"),
                "--format", "json"));
    }

    public JsonObject synchronizeSemanticIndex(String projectId) throws MinosProtocolException {
        return execute(SEMANTIC_INDEX_SYNC, List.of(
                "ide", SEMANTIC_INDEX_SYNC, requireText(projectId, "projectId"),
                "--format", "json"));
    }

    public JsonObject semanticSearch(String projectId, String query, int limit) throws MinosProtocolException {
        return execute(SEMANTIC_SEARCH, List.of(
                "ide", SEMANTIC_SEARCH, requireText(projectId, "projectId"), requireText(query, "query"),
                "--limit", Integer.toString(limit),
                "--format", "json"));
    }

    public JsonObject hybridSearch(String projectId, String query, int limit) throws MinosProtocolException {
        return execute(HYBRID_SEARCH, List.of(
                "ide", HYBRID_SEARCH, requireText(projectId, "projectId"), requireText(query, "query"),
                "--limit", Integer.toString(limit),
                "--format", "json"));
    }

    public JsonObject hybridContext(String projectId, String query, int maxDocuments, int maxTokens)
            throws MinosProtocolException {
        return execute(HYBRID_CONTEXT, List.of(
                "ide", HYBRID_CONTEXT, requireText(projectId, "projectId"), requireText(query, "query"),
                "--max-documents", Integer.toString(maxDocuments),
                "--max-tokens", Integer.toString(maxTokens),
                "--max-tokens-per-document", Integer.toString(Math.min(800, maxTokens)),
                "--format", "json"));
    }

    private JsonObject execute(String capability, List<String> arguments) throws MinosProtocolException {
        requireCapability(capability);
        return client.executeJson(arguments);
    }

    private void requireCapability(String capability) throws MinosProtocolException {
        JsonObject handshake = client.handshake();
        JsonArray capabilities = handshake.has("capabilities") && handshake.get("capabilities").isJsonArray()
                ? handshake.getAsJsonArray("capabilities")
                : new JsonArray();
        List<String> available = new ArrayList<>();
        for (JsonElement element : capabilities) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                available.add(element.getAsString());
            }
        }
        if (!available.contains(capability)) {
            throw new MinosProtocolException(
                    "Connected MINOS runtime does not advertise IDE capability `" + capability + "`; update MINOS before using this action");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
