package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.domain.SymbolLocation;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.analysis.AdvancedImpactService;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.program.analysis.SecurityAnalysisService;
import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Additive M21-S6 transport for IntelliJ M19/M20 parity.
 *
 * <p>This class contains only argument parsing and deterministic JSON projection. All intelligence remains owned by
 * the long-lived {@link MinosApplication} services.</p>
 */
final class IdeIntelligenceCommand {

    private static final String USAGE = """
            Usage: minos ide <operation> ... [--format json]

            Operations:
              program-graph <project> [--max-nodes N] [--max-edges N]
              impact-v2 <project> <symbolId> [--max-depth N] [--max-results N]
              security-paths <project> [--source-node <nodeId>] [--max-depth N] [--max-results N]
              semantic-index-status <project>
              semantic-index-sync <project>
              semantic-search <project> <query> [--limit N] [--minimum-score SCORE]
              hybrid-search <project> <query> [--limit N] [--minimum-score SCORE]
              hybrid-context <project> <query> [--max-documents N] [--max-tokens N] [--max-tokens-per-document N]
            """.stripTrailing();

    private final MinosApplication application;

    IdeIntelligenceCommand(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (arguments.length == 0 || isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }

        try {
            Map<String, Object> result = switch (arguments[0]) {
                case "program-graph" -> programGraph(arguments);
                case "impact-v2" -> impactV2(arguments);
                case "security-paths" -> securityPaths(arguments);
                case "semantic-index-status" -> semanticIndexStatus(arguments);
                case "semantic-index-sync" -> semanticIndexSync(arguments);
                case "semantic-search" -> semanticSearch(arguments);
                case "hybrid-search" -> hybridSearch(arguments);
                case "hybrid-context" -> hybridContext(arguments);
                default -> throw new UsageException("unknown IDE intelligence operation: " + arguments[0]);
            };
            output.append(CliJson.render(result)).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (UsageException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n').append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(message(exception)).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        } catch (IllegalStateException exception) {
            error.append("error: ").append(message(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    static String usage() {
        return USAGE;
    }

    private Map<String, Object> programGraph(String[] arguments) throws IOException {
        requirePositions(arguments, 2, "program-graph requires <project>");
        Options options = options(arguments, 2, Set.of("--max-nodes", "--max-edges", "--format"));
        requireJson(options);
        int maxNodes = options.integer("--max-nodes", ProgramGraphService.DEFAULT_MAX_NODES);
        int maxEdges = options.integer("--max-edges", ProgramGraphService.DEFAULT_MAX_EDGES);
        return graph(application.programGraphService().getGraph(arguments[1], maxNodes, maxEdges));
    }

    private Map<String, Object> impactV2(String[] arguments) throws IOException {
        requirePositions(arguments, 3, "impact-v2 requires <project> <symbolId>");
        Options options = options(arguments, 3, Set.of("--max-depth", "--max-results", "--format"));
        requireJson(options);
        ImpactAnalysisRequest defaults = ImpactAnalysisRequest.defaults(arguments[2]);
        ImpactAnalysisRequest request = new ImpactAnalysisRequest(
                arguments[2],
                options.integer("--max-depth", defaults.maxDepth()),
                options.integer("--max-results", defaults.maxResults()));
        return impact(application.advancedImpactService().analyze(arguments[1], request));
    }

    private Map<String, Object> securityPaths(String[] arguments) throws IOException {
        requirePositions(arguments, 2, "security-paths requires <project>");
        Options options = options(arguments, 2, Set.of("--source-node", "--max-depth", "--max-results", "--format"));
        requireJson(options);
        SecurityAnalysisService.SecurityRequest request = new SecurityAnalysisService.SecurityRequest(
                options.text("--source-node", null),
                options.integer("--max-depth", 8),
                options.integer("--max-results", 100));
        return security(application.securityAnalysisService().analyze(arguments[1], request));
    }

    private Map<String, Object> semanticIndexStatus(String[] arguments) throws IOException {
        requirePositions(arguments, 2, "semantic-index-status requires <project>");
        Options options = options(arguments, 2, Set.of("--format"));
        requireJson(options);
        return semanticStatus(application.semanticIndexService().status(arguments[1]));
    }

    private Map<String, Object> semanticIndexSync(String[] arguments) throws IOException {
        requirePositions(arguments, 2, "semantic-index-sync requires <project>");
        Options options = options(arguments, 2, Set.of("--format"));
        requireJson(options);
        return semanticUpdate(application.semanticIndexService().synchronize(arguments[1]));
    }

    private Map<String, Object> semanticSearch(String[] arguments) throws IOException {
        requirePositions(arguments, 3, "semantic-search requires <project> <query>");
        Options options = options(arguments, 3, Set.of("--limit", "--minimum-score", "--format"));
        requireJson(options);
        SemanticSearchService.SearchRequest defaults = SemanticSearchService.SearchRequest.defaults(arguments[2]);
        SemanticSearchService.SearchRequest request = new SemanticSearchService.SearchRequest(
                arguments[2],
                options.integer("--limit", defaults.limit()),
                options.decimal("--minimum-score", defaults.minimumScore()));
        return semantic(application.semanticSearchService().search(arguments[1], request));
    }

    private Map<String, Object> hybridSearch(String[] arguments) throws IOException {
        requirePositions(arguments, 3, "hybrid-search requires <project> <query>");
        Options options = options(arguments, 3, Set.of("--limit", "--minimum-score", "--format"));
        requireJson(options);
        HybridSearchService.HybridRequest defaults = HybridSearchService.HybridRequest.defaults(arguments[2]);
        HybridSearchService.HybridRequest request = new HybridSearchService.HybridRequest(
                arguments[2],
                options.integer("--limit", defaults.limit()),
                options.decimal("--minimum-score", defaults.minimumScore()));
        return hybrid(application.hybridSearchService().search(arguments[1], request));
    }

    private Map<String, Object> hybridContext(String[] arguments) throws IOException {
        requirePositions(arguments, 3, "hybrid-context requires <project> <query>");
        Options options = options(arguments, 3,
                Set.of("--max-documents", "--max-tokens", "--max-tokens-per-document", "--format"));
        requireJson(options);
        HybridContextBuilder.ContextRequest defaults = HybridContextBuilder.ContextRequest.defaults(arguments[2]);
        HybridContextBuilder.ContextRequest request = new HybridContextBuilder.ContextRequest(
                arguments[2],
                options.integer("--max-documents", defaults.maxDocuments()),
                options.integer("--max-tokens", defaults.maxTokens()),
                options.integer("--max-tokens-per-document", defaults.maxTokensPerDocument()));
        return context(application.hybridContextBuilder().build(arguments[1], request));
    }

    private static Map<String, Object> graph(ProgramGraph graph) {
        return object(
                "projectId", graph.projectId(),
                "snapshotId", graph.snapshotId(),
                "capabilities", graph.capabilities().stream().map(Enum::name).sorted().toList(),
                "nodes", graph.nodes().stream().map(IdeIntelligenceCommand::node).toList(),
                "edges", graph.edges().stream().map(IdeIntelligenceCommand::edge).toList(),
                "limitations", graph.limitations());
    }

    private static Map<String, Object> node(ProgramGraphNode node) {
        return object(
                "id", node.id(),
                "symbolId", node.symbolId(),
                "kind", node.kind().name(),
                "label", node.label(),
                "location", location(node.location()),
                "nature", node.nature().name(),
                "confidence", node.confidence(),
                "providerId", node.origin().providerId());
    }

    private static Map<String, Object> edge(ProgramGraphEdge edge) {
        return object(
                "id", edge.id(),
                "sourceNodeId", edge.sourceNodeId(),
                "targetNodeId", edge.targetNodeId(),
                "kind", edge.kind().name(),
                "nature", edge.nature().name(),
                "confidence", edge.confidence(),
                "providerId", edge.origin().providerId(),
                "evidence", edge.evidence().stream().map(value -> value.description()).toList());
    }

    private static Map<String, Object> location(SymbolLocation location) {
        if (location == null) return null;
        return object(
                "fileId", location.fileId(),
                "startLine", location.startLine(),
                "startColumn", location.startColumn(),
                "endLine", location.endLine(),
                "endColumn", location.endColumn(),
                "positionEncoding", location.positionEncoding().name());
    }

    private static Map<String, Object> impact(AdvancedImpactService.AdvancedImpactReport report) {
        return object(
                "projectId", report.projectId(),
                "snapshotId", report.snapshotId(),
                "baselineImpactCount", report.baselineCount(),
                "advancedAddedCount", report.advancedAddedCount(),
                "totalImpactCount", report.totalCount(),
                "advancedAdded", report.advancedAdded().stream().map(item -> object(
                        "symbolId", item.symbolId(),
                        "label", item.label(),
                        "depth", item.depth(),
                        "confidence", item.confidence(),
                        "nature", item.nature().name(),
                        "programEdgePath", item.programEdgePath())).toList(),
                "limitations", report.limitations());
    }

    private static Map<String, Object> security(SecurityAnalysisService.SecurityReport report) {
        return object(
                "projectId", report.projectId(),
                "snapshotId", report.snapshotId(),
                "observedPaths", report.observedPaths().stream().map(path -> object(
                        "sourceNodeId", path.sourceNodeId(),
                        "sourceLabel", path.sourceLabel(),
                        "sinkNodeId", path.sinkNodeId(),
                        "sinkLabel", path.sinkLabel(),
                        "nodePath", path.nodePath(),
                        "edgePath", path.edgePath(),
                        "sanitizerNodeIds", path.sanitizerNodeIds(),
                        "sanitizedPathObserved", path.sanitizedPathObserved(),
                        "confidence", path.confidence(),
                        "nature", path.nature().name())).toList(),
                "limitations", report.limitations());
    }

    private static Map<String, Object> semanticStatus(SemanticIndexService.Status value) {
        return object(
                "projectId", value.projectId(),
                "projectName", value.projectName(),
                "state", value.state().name(),
                "activeSnapshotId", value.activeSnapshotId(),
                "indexedSnapshotId", value.indexedSnapshotId(),
                "providerId", value.providerId(),
                "modelId", value.modelId(),
                "dimensions", value.dimensions(),
                "documentCount", value.documentCount(),
                "indexSizeBytes", value.indexSizeBytes(),
                "limitations", value.limitations());
    }

    private static Map<String, Object> semanticUpdate(SemanticIndexService.UpdateReport value) {
        return object(
                "projectId", value.projectId(),
                "snapshotId", value.snapshotId(),
                "state", value.state().name(),
                "documentCount", value.documentCount(),
                "embeddedAdded", value.embeddedAdded(),
                "embeddedChanged", value.embeddedChanged(),
                "removed", value.removed(),
                "reused", value.reused(),
                "embeddedCount", value.embeddedCount(),
                "rebuildMillis", value.rebuildMillis(),
                "indexSizeBytes", value.indexSizeBytes(),
                "limitations", value.limitations());
    }

    private static Map<String, Object> semantic(SemanticSearchService.SearchResponse value) {
        return object(
                "projectId", value.projectId(),
                "snapshotId", value.snapshotId(),
                "query", value.query(),
                "mode", value.mode(),
                "hits", value.hits().stream().map(hit -> object(
                        "document", document(hit.document()),
                        "score", hit.score(),
                        "nature", hit.nature().name(),
                        "providerId", hit.providerId(),
                        "modelId", hit.modelId())).toList(),
                "limitations", value.limitations(),
                "latencyMillis", value.latencyMillis());
    }

    private static Map<String, Object> hybrid(HybridSearchService.HybridResponse value) {
        return object(
                "projectId", value.projectId(),
                "snapshotId", value.snapshotId(),
                "query", value.query(),
                "semanticAvailable", value.semanticAvailable(),
                "hits", value.hits().stream().map(hit -> object(
                        "document", document(hit.document()),
                        "score", hit.score(),
                        "lexicalScore", hit.lexicalScore(),
                        "graphScore", hit.graphScore(),
                        "semanticScore", hit.semanticScore(),
                        "rankingMode", hit.rankingMode(),
                        "signals", hit.signals().stream().map(IdeIntelligenceCommand::signal).toList())).toList(),
                "limitations", value.limitations(),
                "latencyMillis", value.latencyMillis());
    }

    private static Map<String, Object> context(HybridContextBuilder.ContextResponse value) {
        return object(
                "projectId", value.projectId(),
                "snapshotId", value.snapshotId(),
                "query", value.query(),
                "maxTokens", value.maxTokens(),
                "usedTokens", value.usedTokens(),
                "truncated", value.truncated(),
                "items", value.items().stream().map(item -> object(
                        "stableKey", item.stableKey(),
                        "kind", item.kind(),
                        "sourceId", item.sourceId(),
                        "fileId", item.fileId(),
                        "startLine", item.startLine(),
                        "endLine", item.endLine(),
                        "content", item.content(),
                        "rankingScore", item.rankingScore(),
                        "rankingMode", item.rankingMode(),
                        "signals", item.signals().stream().map(IdeIntelligenceCommand::signal).toList(),
                        "estimatedTokens", item.estimatedTokens(),
                        "truncated", item.truncated())).toList(),
                "limitations", value.limitations());
    }

    private static Map<String, Object> document(SemanticDocument value) {
        return object(
                "id", value.id(),
                "stableKey", value.stableKey(),
                "kind", value.kind().name(),
                "sourceId", value.sourceId(),
                "fileId", value.fileId(),
                "startLine", value.startLine(),
                "endLine", value.endLine(),
                "contentChecksum", value.checksum());
    }

    private static Map<String, Object> signal(HybridSearchService.RankingSignal value) {
        return object("type", value.type(), "score", value.score(), "nature", value.nature().name());
    }

    private static Options options(String[] arguments, int from, Set<String> allowed) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = from; index < arguments.length; index += 2) {
            String name = arguments[index];
            if (!name.startsWith("--") || !allowed.contains(name)) {
                throw new UsageException("unsupported option: " + name);
            }
            if (index + 1 >= arguments.length) {
                throw new UsageException("missing value for " + name);
            }
            if (values.put(name, arguments[index + 1]) != null) {
                throw new UsageException("duplicate option: " + name);
            }
        }
        return new Options(values);
    }

    private static void requirePositions(String[] arguments, int minimumLength, String failure) {
        if (arguments.length < minimumLength) throw new UsageException(failure);
        for (int index = 1; index < minimumLength; index++) {
            if (arguments[index] == null || arguments[index].isBlank() || arguments[index].startsWith("--")) {
                throw new UsageException(failure);
            }
        }
    }

    private static void requireJson(Options options) {
        String format = options.text("--format", "json");
        if (!"json".equalsIgnoreCase(format)) {
            throw new UsageException("IDE intelligence transport supports only --format json");
        }
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static String message(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("object requires key/value pairs");
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            value.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return value;
    }

    private record Options(Map<String, String> values) {
        private Options {
            values = Map.copyOf(values);
        }

        String text(String name, String defaultValue) {
            return values.getOrDefault(name, defaultValue);
        }

        int integer(String name, int defaultValue) {
            String value = values.get(name);
            if (value == null) return defaultValue;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new UsageException(name + " must be an integer");
            }
        }

        double decimal(String name, double defaultValue) {
            String value = values.get(name);
            if (value == null) return defaultValue;
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw new UsageException(name + " must be a number");
            }
        }
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
