package com.minos.explorer;

import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.output.json.DeterministicJson;
import com.minos.semantic.SemanticDocumentKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The read-only API of the exploration surface, without any HTTP plumbing: a method, the decoded path segments
 * after the version prefix, the query parameters and the body give a status and a JSON document. Every route
 * delegates to {@link SemanticCorpusQueryService}; none writes, synchronizes an index or prepares anything.
 */
final class ExplorerApi {

    /** The routes of contract version 1. {@code POST} is used by the search only, to carry its query in a body. */
    enum Route {
        PROJECTS("GET", "projects"),
        DOCUMENTS("GET", "projects/{project}/documents"),
        DOCUMENT("GET", "projects/{project}/documents/{stableKey}"),
        NEIGHBORS("GET", "projects/{project}/documents/{stableKey}/neighbors"),
        SEARCH("POST", "projects/{project}/search");

        private final String method;
        private final String[] pattern;

        Route(String method, String pattern) {
            this.method = method;
            this.pattern = pattern.split("/");
        }

        String method() {
            return method;
        }

        String template() {
            return String.join("/", pattern);
        }

        boolean matches(List<String> segments) {
            if (segments.size() != pattern.length) return false;
            for (int index = 0; index < pattern.length; index++) {
                if (!pattern[index].startsWith("{") && !pattern[index].equals(segments.get(index))) return false;
            }
            return true;
        }
    }

    /** A status and the JSON document that goes with it; {@code allow} is set on a refused method only. */
    record Answer(int status, String json, String allow) {
        static Answer ok(Map<String, Object> body) {
            return new Answer(200, DeterministicJson.render(body), null);
        }

        static Answer error(int status, String code, String message) {
            return new Answer(status, DeterministicJson.render(DeterministicJson.object(
                    "error", DeterministicJson.object("code", code, "message", message))), null);
        }
    }

    /** The body of a request, read on demand and bounded by the caller. */
    @FunctionalInterface
    interface Body {
        String read() throws IOException;
    }

    static final String INTERNAL_ERROR = "internal error; see the standard error output of minos explorer serve";

    private static final Set<String> DOCUMENT_PARAMETERS =
            Set.of("cursor", "limit", "kind", "module", "language", "filePrefix");
    private static final Set<String> SEARCH_MEMBERS =
            Set.of("query", "mode", "limit", "minimumScore", "kind", "module", "language", "filePrefix");
    private static final int DEFAULT_NEIGHBORS = 20;
    private static final int DEFAULT_SEARCH_LIMIT = 20;

    private final SemanticCorpusQueryService corpus;

    ExplorerApi(SemanticCorpusQueryService corpus) {
        this.corpus = Objects.requireNonNull(corpus, "corpus");
    }

    /**
     * Serves one request. A usage error names what to correct; an internal failure answers an opaque
     * {@code 500} and is reported to {@code failures}, which receives the exception itself and decides what
     * may be logged.
     */
    Answer handle(String method, List<String> segments, Map<String, String> parameters, Body body,
                  java.util.function.Consumer<Exception> failures) {
        Route route = null;
        for (Route candidate : Route.values()) {
            if (candidate.matches(segments)) route = candidate;
        }
        if (route == null) return Answer.error(404, "NOT_FOUND", "no such route in /api/v" + ExplorerContract.VERSION);
        if (!route.method().equals(method)) {
            return new Answer(405, Answer.error(405, "METHOD_NOT_ALLOWED",
                    "this route accepts " + route.method() + " only").json(), route.method());
        }
        try {
            return switch (route) {
                case PROJECTS -> projects(parameters);
                case DOCUMENTS -> documents(segments.get(1), parameters);
                case DOCUMENT -> document(segments.get(1), segments.get(3), parameters);
                case NEIGHBORS -> neighbors(segments.get(1), segments.get(3), parameters);
                case SEARCH -> search(segments.get(1), parameters, body);
            };
        } catch (SemanticCorpusQueryService.DocumentNotFoundException missing) {
            return Answer.error(404, "DOCUMENT_NOT_FOUND", missing.getMessage());
        } catch (SemanticCorpusQueryService.IndexChangedException changed) {
            return Answer.error(409, "INDEX_CHANGED", changed.getMessage());
        } catch (ProjectResolver.ResolutionException unresolved) {
            boolean invalid = unresolved.code() == ProjectResolver.ErrorCode.INVALID_PROJECT_REFERENCE;
            return Answer.error(invalid ? 400 : 404, unresolved.code().name(), unresolved.publicMessage());
        } catch (IllegalArgumentException usage) {
            return Answer.error(400, "INVALID_REQUEST",
                    PublicErrorMessages.sanitize(usage.getMessage(), "invalid request"));
        } catch (IOException | RuntimeException internal) {
            failures.accept(internal);
            return Answer.error(500, "INTERNAL_ERROR", INTERNAL_ERROR);
        }
    }

    private Answer projects(Map<String, String> parameters) throws IOException {
        requireOnly(parameters, Set.of());
        List<Object> projects = new ArrayList<>();
        for (SemanticCorpusQueryService.ProjectSummary project : corpus.projects()) {
            projects.add(DeterministicJson.object(
                    "projectId", project.projectId(),
                    "name", project.name(),
                    "indexState", project.state(),
                    "providerId", project.providerId(),
                    "modelId", project.modelId(),
                    "dimensions", project.dimensions(),
                    "activeSnapshotId", project.activeSnapshotId(),
                    "indexedSnapshotId", project.indexedSnapshotId(),
                    "documentCount", project.documentCount(),
                    "limitations", project.limitations()));
        }
        return Answer.ok(DeterministicJson.object("contractVersion", ExplorerContract.VERSION, "projects", projects));
    }

    private Answer documents(String project, Map<String, String> parameters) throws IOException {
        requireOnly(parameters, DOCUMENT_PARAMETERS);
        SemanticCorpusQueryService.DocumentsPage page = corpus.documents(project,
                new SemanticCorpusQueryService.DocumentsRequest(parameters.get("cursor"),
                        integer(parameters.get("limit"), "limit", SemanticCorpusQueryService.DEFAULT_PAGE_SIZE),
                        kind(parameters.get("kind")), parameters.get("module"), parameters.get("language"),
                        parameters.get("filePrefix")));
        return Answer.ok(DeterministicJson.object(
                "projectId", page.projectId(),
                "index", page.identity().map(ExplorerApi::identity).orElse(null),
                "totalDocuments", page.totalDocuments(),
                "documents", page.documents().stream().map(ExplorerApi::document).toList(),
                "nextCursor", page.nextCursor(),
                "limitations", page.limitations()));
    }

    private Answer document(String project, String stableKey, Map<String, String> parameters) throws IOException {
        requireOnly(parameters, Set.of());
        SemanticCorpusQueryService.Detail detail = corpus.detail(project, stableKey);
        Map<String, Object> source = detail.source().map(value -> DeterministicJson.object(
                "fileId", value.fileId(),
                "startLine", value.startLine(),
                "endLine", value.endLine(),
                "content", value.content(),
                "truncated", value.truncated())).orElse(null);
        return Answer.ok(DeterministicJson.object(
                "projectId", detail.projectId(),
                "index", identity(detail.identity()),
                "document", document(detail.document()),
                "indexedContent", detail.indexedContent(),
                "currentSource", source,
                "divergence", detail.divergence().name(),
                "limitations", detail.limitations()));
    }

    private Answer neighbors(String project, String stableKey, Map<String, String> parameters) throws IOException {
        requireOnly(parameters, Set.of("limit"));
        SemanticCorpusQueryService.Neighbors neighbors = corpus.neighbors(project, stableKey,
                integer(parameters.get("limit"), "limit", DEFAULT_NEIGHBORS));
        List<Object> ranked = new ArrayList<>();
        for (SemanticCorpusQueryService.Neighbor neighbor : neighbors.neighbors()) {
            ranked.add(DeterministicJson.object(
                    "document", document(neighbor.document()),
                    "score", neighbor.similarity(),
                    "scoreKind", SemanticCorpusQueryService.ScoreKind.COSINE_SIMILARITY.name()));
        }
        return Answer.ok(DeterministicJson.object(
                "projectId", neighbors.projectId(),
                "index", identity(neighbors.identity()),
                "stableKey", neighbors.stableKey(),
                "neighbors", ranked,
                "limitations", neighbors.limitations()));
    }

    private Answer search(String project, Map<String, String> parameters, Body body) throws IOException {
        requireOnly(parameters, Set.of());
        Map<String, Object> members = JsonObjectReader.read(body.read());
        for (String member : members.keySet()) {
            if (!SEARCH_MEMBERS.contains(member)) throw new IllegalArgumentException("unknown search member");
        }
        SemanticCorpusQueryService.SearchMode mode = mode(text(members, "mode"));
        SemanticCorpusQueryService.SearchResult result = corpus.search(project,
                new SemanticCorpusQueryService.SearchRequest(text(members, "query"), mode,
                        (int) number(members, "limit", DEFAULT_SEARCH_LIMIT, true),
                        number(members, "minimumScore", 0.0, false), kind(text(members, "kind")),
                        text(members, "module"), text(members, "language"), text(members, "filePrefix")));
        List<Object> hits = new ArrayList<>();
        for (SemanticCorpusQueryService.SearchHit hit : result.hits()) {
            hits.add(DeterministicJson.object(
                    "rank", hit.rank(),
                    "document", document(hit.document()),
                    "score", hit.score(),
                    "scoreKind", hit.scoreKind(),
                    "signals", hit.signals().stream().map(signal -> DeterministicJson.object(
                            "type", signal.type(), "score", signal.score(), "nature", signal.nature())).toList()));
        }
        return Answer.ok(DeterministicJson.object(
                "projectId", result.projectId(),
                "index", result.identity().map(ExplorerApi::identity).orElse(null),
                "snapshotId", result.snapshotId(),
                "mode", result.mode(),
                "query", result.query(),
                "rankedCount", result.rankedCount(),
                "hits", hits,
                "limitations", result.limitations(),
                "latencyMillis", result.latencyMillis()));
    }

    private static Map<String, Object> identity(SemanticCorpusQueryService.IndexIdentity identity) {
        return DeterministicJson.object(
                "snapshotId", identity.snapshotId(),
                "providerId", identity.providerId(),
                "modelId", identity.modelId(),
                "dimensions", identity.dimensions(),
                "documentsFingerprint", identity.documentsFingerprint());
    }

    private static Map<String, Object> document(SemanticCorpusQueryService.DocumentSummary document) {
        Map<String, Object> symbol = !document.symbolResolved() ? null : DeterministicJson.object(
                "name", document.symbolName(),
                "qualifiedName", document.qualifiedName(),
                "kind", document.symbolKind());
        return DeterministicJson.object(
                "stableKey", document.stableKey(),
                "kind", document.kind(),
                "sourceId", document.sourceId(),
                "fileId", document.fileId(),
                "startLine", document.startLine(),
                "endLine", document.endLine(),
                "contentChecksum", document.checksum(),
                "contentLength", document.contentLength(),
                "symbol", symbol,
                "language", document.language(),
                "moduleId", document.moduleId());
    }

    private static void requireOnly(Map<String, String> parameters, Set<String> accepted) {
        for (String name : parameters.keySet()) {
            if (!accepted.contains(name)) throw new IllegalArgumentException("unknown query parameter");
        }
    }

    private static int integer(String value, String name, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static SemanticDocumentKind kind(String value) {
        if (value == null) return null;
        try {
            return SemanticDocumentKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("kind must be SYMBOL, CHUNK or FILE");
        }
    }

    private static SemanticCorpusQueryService.SearchMode mode(String value) {
        if (value == null) return SemanticCorpusQueryService.SearchMode.SEMANTIC;
        try {
            return SemanticCorpusQueryService.SearchMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("mode must be semantic or hybrid");
        }
    }

    private static String text(Map<String, Object> members, String name) {
        Object value = members.get(name);
        if (value == null) return null;
        if (value instanceof String string) return string;
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static double number(Map<String, Object> members, String name, double defaultValue, boolean integral) {
        Object value = members.get(name);
        if (value == null) return defaultValue;
        if (!(value instanceof Double number) || (integral && (number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE))) {
            throw new IllegalArgumentException(name + " must be " + (integral ? "an integer" : "a number"));
        }
        return number;
    }

    /** The templates of the routes, by method, for the inventory of what the surface accepts. */
    static Map<String, List<String>> inventory() {
        Map<String, List<String>> byMethod = new LinkedHashMap<>();
        for (Route route : Route.values()) {
            byMethod.computeIfAbsent(route.method(), ignored -> new ArrayList<>()).add(route.template());
        }
        return byMethod;
    }
}
