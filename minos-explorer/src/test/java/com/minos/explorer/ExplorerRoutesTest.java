package com.minos.explorer;

import com.minos.application.semantic.SemanticSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routes of the surface on a real indexed home, through real sockets: what each one answers, that none
 * writes anything, that no answer names an absolute path, and that the search is the one of the services.
 */
class ExplorerRoutesTest {

    private static final Pattern STABLE_KEY = Pattern.compile("\"stableKey\":\"([^\"]+)\"");

    private final List<String> log = new CopyOnWriteArrayList<>();
    private IndexedHome fixture;
    private ExplorerServer server;
    private int port;
    private Map<String, String> bearer;
    private String documents;

    @BeforeEach
    void start(@TempDir Path temp) throws Exception {
        fixture = IndexedHome.create(temp);
        server = ExplorerServer.start(fixture.application.semanticCorpusQueryService(), 0,
                ListeningPolicy.Scope.LOOPBACK, Duration.ofSeconds(20), log::add);
        port = server.port();
        String url = server.accessUrl();
        bearer = Map.of("Authorization", "Bearer " + url.substring(url.indexOf("#token=") + 7));
        documents = "/api/v1/projects/" + fixture.projectId + "/documents";
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        fixture.close();
    }

    @Test
    void theProjectListDescribesTheIndexAndNeverTheRootOfAProject() throws Exception {
        RawHttp.Response projects = RawHttp.get(port, "/api/v1/projects", bearer);

        assertEquals(200, projects.status());
        assertTrue(projects.body().startsWith("{\"contractVersion\":\"1\",\"projects\":[{"), projects.body());
        assertTrue(projects.body().contains("\"name\":\"" + IndexedHome.PROJECT + "\""));
        assertTrue(projects.body().contains("\"indexState\":\"READY\""));
        assertTrue(projects.body().contains("\"providerId\":\"minos-local-hash\""));
        assertTrue(projects.body().contains("\"dimensions\":64"));
        assertTrue(projects.body().contains("\"documentCount\":6"));
        assertTrue(projects.body().contains("\"indexedSnapshotId\":\"snapshot-1\""));
        assertTrue(projects.body().contains("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"));
        assertNoAbsolutePath(projects.body());
    }

    @Test
    void theDocumentsAreWalkedByCursorOnOneIndexAndAPageSizeOutOfBoundsNamesTheBound() throws Exception {
        List<String> walked = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        String cursor = null;
        do {
            RawHttp.Response page = RawHttp.get(port, documents + "?limit=4"
                    + (cursor == null ? "" : "&cursor=" + encode(cursor)), bearer);
            assertEquals(200, page.status(), page.body());
            assertTrue(page.body().contains("\"totalDocuments\":6"));
            walked.addAll(stableKeys(page.body()));
            fingerprints.add(member(page.body(), "documentsFingerprint"));
            cursor = member(page.body(), "nextCursor");
        } while (cursor != null);
        RawHttp.Response tooLarge = RawHttp.get(port, documents + "?limit=5000", bearer);
        RawHttp.Response notANumber = RawHttp.get(port, documents + "?limit=many", bearer);
        RawHttp.Response unknownParameter = RawHttp.get(port, documents + "?order=desc", bearer);
        RawHttp.Response unknownKind = RawHttp.get(port, documents + "?kind=MODULE", bearer);

        assertEquals(6, walked.size());
        assertEquals(walked.stream().sorted().toList(), walked);
        assertEquals(6, walked.stream().distinct().count());
        assertEquals(1, fingerprints.stream().distinct().count());
        assertEquals(400, tooLarge.status());
        assertTrue(tooLarge.body().contains("limit must be between 1 and 200"), tooLarge.body());
        assertEquals(400, notANumber.status());
        assertEquals(400, unknownParameter.status());
        assertEquals(400, unknownKind.status());
        assertTrue(unknownKind.body().contains("kind must be SYMBOL, CHUNK or FILE"));
    }

    @Test
    void aCursorOfAReplacedIndexIsAConflictThatAsksToStartAgain() throws Exception {
        String cursor = member(RawHttp.get(port, documents + "?limit=2", bearer).body(), "nextCursor");
        Files.writeString(fixture.projectRoot.resolve("src/Parting.java"),
                "package fixture;\n\nclass PartingService {\n    String part() { return \"farewell\"; }\n}\n",
                StandardCharsets.UTF_8);
        fixture.application.semanticVectorStore().delete(fixture.projectId);
        fixture.application.semanticIndexService().synchronize(IndexedHome.PROJECT);

        RawHttp.Response conflict = RawHttp.get(port, documents + "?limit=2&cursor=" + encode(cursor), bearer);

        assertEquals(409, conflict.status(), conflict.body());
        assertTrue(conflict.body().contains("\"code\":\"INDEX_CHANGED\""), conflict.body());
        assertTrue(conflict.body().contains("start the reading again"), conflict.body());
        assertTrue(stableKeys(conflict.body()).isEmpty(), "no document of the new index in a refused page");
    }

    @Test
    void theDetailSeparatesTheIndexedContentFromTheSourceAndCarriesHtmlAsJsonText() throws Exception {
        String chunk = chunkKey("GreetingService");
        RawHttp.Response unchanged = RawHttp.get(port, documents + "/" + encode(chunk), bearer);
        Files.writeString(fixture.projectRoot.resolve("src/Greeting.java"),
                "package fixture;\n\nclass GreetingService {\n    String greet() { return \"changed\"; }\n}\n",
                StandardCharsets.UTF_8);
        RawHttp.Response changed = RawHttp.get(port, documents + "/" + encode(chunk), bearer);
        Files.delete(fixture.projectRoot.resolve("src/Greeting.java"));
        RawHttp.Response deleted = RawHttp.get(port, documents + "/" + encode(chunk), bearer);
        RawHttp.Response file = RawHttp.get(port, documents + "/" + encode("file:src/Parting.java"), bearer);
        RawHttp.Response unknown = RawHttp.get(port, documents + "/" + encode("chunk:absent"), bearer);

        assertEquals(200, unchanged.status(), unchanged.body());
        assertEquals("application/json; charset=utf-8", unchanged.header("Content-Type"));
        assertEquals("nosniff", unchanged.header("X-Content-Type-Options"));
        assertTrue(unchanged.body().contains("\"divergence\":\"IDENTIQUE\""), unchanged.body());
        assertTrue(unchanged.body().contains(IndexedHome.SCRIPT.replace("/", "\\/"))
                || unchanged.body().contains(IndexedHome.SCRIPT), "the indexed content is carried as JSON text");
        assertTrue(unchanged.body().contains("\"symbol\":{\"name\":\"GreetingService\""), unchanged.body());
        assertTrue(changed.body().contains("\"divergence\":\"DIFFERENTE\""), changed.body());
        assertTrue(changed.body().contains("changed"));
        assertTrue(changed.body().contains("alert(1)"), "the indexed content is still shown");
        assertTrue(deleted.body().contains("\"divergence\":\"SOURCE_INTROUVABLE\""), deleted.body());
        assertTrue(deleted.body().contains("\"currentSource\":null"), deleted.body());
        assertTrue(deleted.body().contains("alert(1)"));
        assertTrue(file.body().contains("\"divergence\":\"NON_COMPARABLE\""), file.body());
        assertEquals(404, unknown.status());
        assertTrue(unknown.body().contains("\"code\":\"DOCUMENT_NOT_FOUND\""), unknown.body());
        for (RawHttp.Response answer : List.of(unchanged, changed, deleted, file, unknown)) {
            assertNoAbsolutePath(answer.body());
        }
    }

    @Test
    void theNeighboursLeaveTheDocumentOutNameTheirScoreAndRefuseAnUnknownKey() throws Exception {
        String chunk = chunkKey("GreetingService");
        RawHttp.Response neighbors = RawHttp.get(port, documents + "/" + encode(chunk) + "/neighbors?limit=3", bearer);
        RawHttp.Response unknown = RawHttp.get(port, documents + "/" + encode("chunk:absent") + "/neighbors", bearer);
        RawHttp.Response tooMany = RawHttp.get(port, documents + "/" + encode(chunk) + "/neighbors?limit=201", bearer);

        assertEquals(200, neighbors.status(), neighbors.body());
        List<String> keys = stableKeys(neighbors.body());
        assertEquals(chunk, keys.getFirst(), "the first stable key is the document asked for");
        assertEquals(3, keys.size() - 1);
        assertFalse(keys.subList(1, keys.size()).contains(chunk));
        assertTrue(neighbors.body().contains("\"scoreKind\":\"COSINE_SIMILARITY\""));
        assertTrue(neighbors.body().contains("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"));
        assertEquals(404, unknown.status());
        assertEquals(400, tooMany.status());
        assertTrue(tooMany.body().contains("limit must be between 1 and 200"));
    }

    @Test
    void aSearchRanksLikeTheSemanticServiceAndAFilterOnlyRemovesHits() throws Exception {
        SemanticSearchService.SearchResponse direct = fixture.application.semanticSearchService().search(
                IndexedHome.PROJECT, new SemanticSearchService.SearchRequest("greet service", 10, -1.0));
        Map<String, String> json = json();

        RawHttp.Response all = RawHttp.send(port, "POST", "/api/v1/projects/" + fixture.projectId + "/search", json,
                "{\"query\":\"greet service\",\"mode\":\"semantic\",\"limit\":10,\"minimumScore\":-1}");
        RawHttp.Response files = RawHttp.send(port, "POST", "/api/v1/projects/" + fixture.projectId + "/search", json,
                "{\"query\":\"greet service\",\"limit\":10,\"minimumScore\":-1,\"kind\":\"FILE\"}");
        RawHttp.Response hybrid = RawHttp.send(port, "POST", "/api/v1/projects/" + fixture.projectId + "/search", json,
                "{\"query\":\"GreetingService\",\"mode\":\"hybrid\"}");

        assertEquals(200, all.status(), all.body());
        assertEquals(direct.hits().stream().map(hit -> hit.document().stableKey()).toList(), stableKeys(all.body()));
        assertFalse(direct.hits().isEmpty());
        assertEquals(direct.hits().stream().map(hit -> Double.toString(hit.score())).toList(),
                scoresOfHits(all.body()));
        assertTrue(all.body().contains("\"scoreKind\":\"COSINE_SIMILARITY\""));
        assertTrue(all.body().contains("\"rankedCount\":" + direct.hits().size()));
        assertTrue(all.body().contains("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"));
        assertEquals(2, stableKeys(files.body()).size());
        assertTrue(stableKeys(all.body()).containsAll(stableKeys(files.body())));
        assertTrue(files.body().contains("\"rankedCount\":" + direct.hits().size()));
        assertEquals(200, hybrid.status(), hybrid.body());
        assertTrue(hybrid.body().contains("\"scoreKind\":\"HYBRID_SCORE\""), hybrid.body());
        assertTrue(hybrid.body().contains("\"signals\":[{\"type\":"), hybrid.body());
        assertNoAbsolutePath(all.body());
    }

    @Test
    void aSearchOnAStaleIndexIsEmptyAndItsUsageErrorsNameWhatToCorrect() throws Exception {
        Map<String, String> json = json();
        String search = "/api/v1/projects/" + fixture.projectId + "/search";
        RawHttp.Response blank = RawHttp.send(port, "POST", search, json, "{\"query\":\" \"}");
        RawHttp.Response nested = RawHttp.send(port, "POST", search, json, "{\"query\":{\"a\":1}}");
        RawHttp.Response unknownMember = RawHttp.send(port, "POST", search, json, "{\"query\":\"x\",\"sync\":true}");
        RawHttp.Response tooMany = RawHttp.send(port, "POST", search, json,
                "{\"query\":\"x\",\"mode\":\"hybrid\",\"limit\":501}");
        RawHttp.Response get = RawHttp.get(port, search, bearer);
        fixture.application.snapshotStore().publish(java.util.UUID.fromString(fixture.projectId), "snapshot-2",
                List.of(), List.of(), List.of());

        RawHttp.Response stale = RawHttp.send(port, "POST", search, json, "{\"query\":\"greet\"}");
        RawHttp.Response staleList = RawHttp.get(port, documents, bearer);

        assertEquals(400, blank.status());
        assertTrue(blank.body().contains("query must not be blank"), blank.body());
        assertEquals(400, nested.status());
        assertEquals(400, unknownMember.status());
        assertEquals(400, tooMany.status());
        assertTrue(tooMany.body().contains("limit must be between 1 and 500"), tooMany.body());
        assertEquals(405, get.status());
        assertEquals("POST", get.header("Allow"));
        assertEquals(200, stale.status(), stale.body());
        assertTrue(stale.body().contains("\"hits\":[]"), stale.body());
        assertTrue(stale.body().contains("SEMANTIC_SEARCH_REQUIRES_READY_INDEX"), stale.body());
        assertEquals(200, staleList.status(), "a stale index is still listed");
        assertTrue(staleList.body().contains("SEMANTIC_INDEX_SNAPSHOT_STALE"), staleList.body());
        assertTrue(staleList.body().contains("SEMANTIC_DOCUMENT_SYMBOLS_UNRESOLVED_INDEX_NOT_ALIGNED"));
    }

    @Test
    void anUnknownProjectOrRouteIsNotFoundAndNoMethodButGetAndTheSearchPostIsAccepted() throws Exception {
        RawHttp.Response unknownProject = RawHttp.get(port, "/api/v1/projects/absent-project/documents", bearer);
        RawHttp.Response unknownRoute = RawHttp.get(port, "/api/v1/indexes", bearer);

        assertEquals(404, unknownProject.status(), unknownProject.body());
        assertEquals(404, unknownRoute.status());
        assertEquals(Map.of("GET", List.of("projects", "projects/{project}/documents",
                        "projects/{project}/documents/{stableKey}",
                        "projects/{project}/documents/{stableKey}/neighbors"),
                "POST", List.of("projects/{project}/search")), ExplorerApi.inventory());
        String chunk = encode(chunkKey("GreetingService"));
        List<String> paths = List.of("/api/v1/projects", documents, documents + "/" + chunk,
                documents + "/" + chunk + "/neighbors", "/api/v1/projects/" + fixture.projectId + "/search");
        for (String path : paths) {
            for (String method : List.of("PUT", "PATCH", "DELETE", "POST")) {
                boolean search = path.endsWith("/search");
                if (search && "POST".equals(method)) continue;
                RawHttp.Response refused = RawHttp.send(port, method, path, json(), "{}");
                assertEquals(405, refused.status(), method + " " + path);
                assertEquals(search ? "POST" : "GET", refused.header("Allow"), method + " " + path);
            }
        }
    }

    @Test
    void consultingEveryRouteLeavesTheHomeAndTheIndexByteForByteUnchanged() throws Exception {
        Map<String, String> before = tree(fixture.home);
        String chunk = encode(chunkKey("GreetingService"));
        String search = "/api/v1/projects/" + fixture.projectId + "/search";

        List<RawHttp.Response> answers = List.of(
                RawHttp.get(port, "/", Map.of()),
                RawHttp.get(port, "/api/v1/projects", bearer),
                RawHttp.get(port, documents + "?limit=2&kind=CHUNK&language=java&module=module-a&filePrefix=src", bearer),
                RawHttp.get(port, documents + "/" + chunk, bearer),
                RawHttp.get(port, documents + "/" + chunk + "/neighbors", bearer),
                RawHttp.send(port, "POST", search, json(), "{\"query\":\"greet\",\"mode\":\"semantic\"}"),
                RawHttp.send(port, "POST", search, json(), "{\"query\":\"greet\",\"mode\":\"hybrid\"}"),
                RawHttp.send(port, "PUT", documents + "/" + chunk, json(), "{}"),
                RawHttp.send(port, "DELETE", documents + "/" + chunk, json(), "{}"),
                RawHttp.get(port, documents + "/" + encode("chunk:absent"), bearer));

        assertEquals(List.of(200, 200, 200, 200, 200, 200, 200, 405, 405, 404),
                answers.stream().map(RawHttp.Response::status).toList());
        assertEquals(before, tree(fixture.home), "MINOS_HOME after the consultation");
        assertFalse(before.isEmpty());
    }

    @Test
    void noAnswerErrorOrLogLineNamesAnAbsoluteRootOrAProviderAddress() throws Exception {
        String chunk = encode(chunkKey("GreetingService"));
        List<RawHttp.Response> answers = List.of(
                RawHttp.get(port, "/api/v1/projects", bearer),
                RawHttp.get(port, documents, bearer),
                RawHttp.get(port, documents + "/" + chunk, bearer),
                RawHttp.get(port, "/api/v1/projects/" + encode(fixture.projectRoot.toString()) + "/documents", bearer),
                RawHttp.get(port, "/api/v1/projects/" + encode("jdbc:postgresql://user:secret@db/minos") + "/documents", bearer),
                RawHttp.get(port, documents + "?cursor=" + encode(fixture.projectRoot.toString()), bearer),
                RawHttp.get(port, documents + "/" + chunk, Map.of()));

        for (RawHttp.Response answer : answers) {
            assertNoAbsolutePath(answer.raw());
            assertFalse(answer.raw().contains("secret@db"), answer.raw());
            assertFalse(answer.raw().contains("11434"), "provider address in " + answer.raw());
        }
        for (String line : log) assertNoAbsolutePath(line);
    }

    private void assertNoAbsolutePath(String text) {
        String normalized = text.replace("\\\\", "\\");
        assertFalse(normalized.contains(fixture.projectRoot.toString()), "project root in: " + text);
        assertFalse(normalized.contains(fixture.home.toString()), "MINOS_HOME in: " + text);
        assertFalse(normalized.contains(fixture.projectRoot.toString().replace('\\', '/')), "project root in: " + text);
        assertFalse(normalized.contains(fixture.projectRoot.getParent().getFileName().toString()), "temp folder in: " + text);
    }

    private Map<String, String> json() {
        Map<String, String> headers = new LinkedHashMap<>(bearer);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private String chunkKey(String symbol) throws IOException {
        return stableKeys(RawHttp.get(port, documents + "?kind=CHUNK", bearer).body()).stream()
                .filter(key -> key.contains(symbol)).findFirst().orElseThrow();
    }

    private static List<String> stableKeys(String body) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = STABLE_KEY.matcher(body);
        while (matcher.find()) keys.add(matcher.group(1));
        return keys;
    }

    private static List<String> scoresOfHits(String body) {
        List<String> scores = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\},\"score\":([-0-9.eE]+),\"scoreKind\"").matcher(body);
        while (matcher.find()) scores.add(matcher.group(1));
        return scores;
    }

    private static String member(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":(null|\"([^\"]*)\")").matcher(body);
        if (!matcher.find()) throw new IllegalStateException("no member " + name + " in " + body);
        return matcher.group(2);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Every file under {@code root}, by relative path, with the SHA-256 of its content. */
    private static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    files.put(relative + "/", "directory");
                } else {
                    files.put(relative, HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                }
            }
        }
        return files;
    }
}
