package com.minos.explorer;

import com.minos.application.semantic.SemanticProjectionService;
import com.minos.semantic.SemanticDocumentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two projection routes of the surface, through real sockets on a real indexed home: they read the state
 * and the points of a projection prepared elsewhere, compute nothing, and no request prepares one.
 */
class ExplorerProjectionRoutesTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private IndexedHome fixture;
    private ExplorerServer server;
    private int port;
    private Map<String, String> bearer;
    private String projection;

    @BeforeEach
    void start(@TempDir Path temp) throws Exception {
        fixture = IndexedHome.create(temp);
        server = ExplorerServer.start(fixture.application.semanticCorpusQueryService(),
                fixture.application.semanticProjectionService(), 0, ListeningPolicy.Scope.LOOPBACK,
                Duration.ofSeconds(20), log::add);
        port = server.port();
        String url = server.accessUrl();
        bearer = Map.of("Authorization", "Bearer " + url.substring(url.indexOf("#token=") + 7));
        projection = "/api/v1/projects/" + fixture.projectId + "/projection";
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        fixture.close();
    }

    @Test
    void aProjectWithoutProjectionIsAbsentAndTheAnswerNamesTheCommandThatPreparesIt() throws Exception {
        Map<String, String> before = ExplorerRoutesTest.tree(fixture.home);

        RawHttp.Response status = RawHttp.get(port, projection, bearer);
        RawHttp.Response points = RawHttp.get(port, projection + "/points", bearer);

        assertEquals(200, status.status(), status.body());
        assertTrue(status.body().contains("\"state\":\"ABSENTE\""), status.body());
        assertTrue(status.body().contains(
                "\"prepareCommand\":\"minos semantic projection prepare " + fixture.projectId + "\""), status.body());
        assertTrue(status.body().contains("\"indexDocumentCount\":6"), status.body());
        assertTrue(status.body().contains("\"projection\":null"), status.body());
        assertEquals(200, points.status(), points.body());
        assertTrue(points.body().contains("\"state\":\"ABSENTE\""), points.body());
        assertTrue(points.body().contains("\"points\":[]"), points.body());
        assertTrue(points.body().contains("minos semantic projection prepare "), points.body());
        assertEquals(before, ExplorerRoutesTest.tree(fixture.home), "reading an absent projection creates nothing");
        assertFalse(Files.exists(fixture.home.resolve("semantic-projections")));
    }

    @Test
    void anyMethodButGetOnAProjectionRouteIsRefusedAndNoProjectionIsCreatedOrChanged() throws Exception {
        for (String path : List.of(projection, projection + "/points")) {
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                RawHttp.Response refused = RawHttp.send(port, method, path, json(), "{\"maxPoints\":10}");
                assertEquals(405, refused.status(), method + " " + path);
                assertEquals("GET", refused.header("Allow"), method + " " + path);
            }
        }
        assertFalse(Files.exists(fixture.home.resolve("semantic-projections")), "no request prepares a projection");

        prepare(3, 11L);
        Map<String, String> prepared = ExplorerRoutesTest.tree(fixture.home);
        RawHttp.Response post = RawHttp.send(port, "POST", projection, json(), "{}");
        RawHttp.Response delete = RawHttp.send(port, "DELETE", projection, json(), "{}");
        RawHttp.get(port, projection, bearer);
        RawHttp.get(port, projection + "/points?offset=0&limit=2", bearer);

        assertEquals(405, post.status());
        assertEquals(405, delete.status());
        assertEquals(prepared, ExplorerRoutesTest.tree(fixture.home), "MINOS_HOME after the requests");
        assertEquals(404, RawHttp.send(port, "POST", projection + "/prepare", json(), "{}").status());
    }

    @Test
    void theRoutesAreApiRoutesTheyAnswerNothingWithoutTheTokenOrFromAnotherSite() throws Exception {
        Map<String, String> crossSite = new LinkedHashMap<>(bearer);
        crossSite.put("Sec-Fetch-Site", "cross-site");
        Map<String, String> foreignOrigin = new LinkedHashMap<>(bearer);
        foreignOrigin.put("Origin", "http://evil.example");

        for (String path : List.of(projection, projection + "/points")) {
            RawHttp.Response anonymous = RawHttp.get(port, path, Map.of());
            RawHttp.Response linked = RawHttp.get(port, path, crossSite);
            RawHttp.Response foreign = RawHttp.get(port, path, foreignOrigin);

            assertEquals(401, anonymous.status(), path);
            assertEquals("", anonymous.body());
            assertEquals(403, linked.status(), path);
            assertEquals(403, foreign.status(), path);
            assertNull(foreign.header("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void aSampledProjectionAnnouncesItsMethodItsSeedAndItsCountsWithItsPoints() throws Exception {
        prepare(4, 4_242L);

        RawHttp.Response status = RawHttp.get(port, projection, bearer);
        RawHttp.Response first = RawHttp.get(port, projection + "/points?limit=3", bearer);
        RawHttp.Response rest = RawHttp.get(port, projection + "/points?offset=3&limit=3", bearer);

        assertEquals(200, status.status(), status.body());
        for (RawHttp.Response answer : List.of(status, first, rest)) {
            assertTrue(answer.body().contains("\"state\":\"PRETE\""), answer.body());
            assertTrue(answer.body().contains("\"method\":\"pca\""), answer.body());
            assertTrue(answer.body().contains("\"seed\":4242"), answer.body());
            assertTrue(answer.body().contains("\"pointCount\":4"), answer.body());
            assertTrue(answer.body().contains("\"documentCount\":6"), answer.body());
            assertTrue(answer.body().contains("\"candidateCount\":6"), answer.body());
            assertTrue(answer.body().contains("\"sampled\":true"), answer.body());
            assertTrue(answer.body().contains("\"kinds\":[\"CHUNK\",\"FILE\",\"SYMBOL\"]"), answer.body());
            assertTrue(answer.body().contains("\"parameters\":\"components=2;"), answer.body());
            assertTrue(answer.body().contains("\"explainedVariance\":["), answer.body());
            assertTrue(answer.body().contains("\"snapshotId\":\"snapshot-1\""), answer.body());
            assertFalse(answer.body().contains(fixture.home.toString()), "no absolute path in " + answer.body());
            assertFalse(answer.body().contains(fixture.projectRoot.toString()));
        }
        assertEquals(3, count(first.body(), "\"stableKey\":"));
        assertEquals(1, count(rest.body(), "\"stableKey\":"));
        assertTrue(first.body().contains("\"total\":4"), first.body());
        assertTrue(first.body().contains("\"offset\":0"), first.body());
        assertTrue(rest.body().contains("\"offset\":3"), rest.body());
        assertTrue(first.body().contains("\"x\":") && first.body().contains("\"y\":") && first.body().contains("\"kind\":\""));
    }

    @Test
    void aProjectionWhoseIndexWasReplacedIsStaleAndAnAlteredOneIsFailed() throws Exception {
        prepare(6, 1L);
        fixture.application.semanticVectorStore().delete(fixture.projectId);
        RawHttp.Response stale = RawHttp.get(port, projection, bearer);
        RawHttp.Response stalePoints = RawHttp.get(port, projection + "/points", bearer);

        Path file = fixture.home.resolve("semantic-projections").resolve(fixture.projectId).resolve("projection-v1.bin");
        Files.write(file, new byte[]{1, 2, 3, 4});
        RawHttp.Response failed = RawHttp.get(port, projection, bearer);
        RawHttp.Response failedPoints = RawHttp.get(port, projection + "/points", bearer);

        assertTrue(stale.body().contains("\"state\":\"PERIMEE\""), stale.body());
        assertEquals(6, count(stalePoints.body(), "\"stableKey\":"), "a stale projection is still shown, as stale");
        assertTrue(failed.body().contains("\"state\":\"ECHOUEE\""), failed.body());
        assertTrue(failed.body().contains("\"cause\":\"PROJECTION_UNREADABLE\""), failed.body());
        assertEquals(200, failedPoints.status());
        assertTrue(failedPoints.body().contains("\"points\":[]"), failedPoints.body());
        assertFalse(failed.body().contains(file.toString()));
    }

    @Test
    void aPageOutOfBoundsAnUnknownParameterOrAnUnknownProjectIsAUsageErrorThatNamesWhatToCorrect() throws Exception {
        RawHttp.Response tooMany = RawHttp.get(port, projection + "/points?limit=5001", bearer);
        RawHttp.Response negative = RawHttp.get(port, projection + "/points?offset=-1", bearer);
        RawHttp.Response notANumber = RawHttp.get(port, projection + "/points?offset=abc", bearer);
        RawHttp.Response unknownParameter = RawHttp.get(port, projection + "?recompute=true", bearer);
        RawHttp.Response unknownPointsParameter = RawHttp.get(port, projection + "/points?prepare=1", bearer);
        RawHttp.Response unknownProject = RawHttp.get(port, "/api/v1/projects/absent-project/projection", bearer);

        assertEquals(400, tooMany.status());
        assertTrue(tooMany.body().contains("limit must be between 1 and 5000"), tooMany.body());
        assertEquals(400, negative.status());
        assertTrue(negative.body().contains("offset must not be negative"), negative.body());
        assertEquals(400, notANumber.status());
        assertEquals(400, unknownParameter.status());
        assertEquals(400, unknownPointsParameter.status());
        assertEquals(404, unknownProject.status(), unknownProject.body());
        assertEquals(Map.of("GET", List.of("projects", "projects/{project}/documents",
                        "projects/{project}/documents/{stableKey}",
                        "projects/{project}/documents/{stableKey}/neighbors",
                        "projects/{project}/projection", "projects/{project}/projection/points"),
                "POST", List.of("projects/{project}/search")), ExplorerApi.inventory());
    }

    private void prepare(int maxPoints, long seed) throws IOException {
        fixture.application.semanticProjectionService().prepare(fixture.projectId,
                new SemanticProjectionService.PreparationRequest(maxPoints, seed,
                        Set.of(SemanticDocumentKind.values()), 64L * 1024L * 1024L),
                SemanticProjectionService.Progress.NONE);
    }

    private Map<String, String> json() {
        Map<String, String> headers = new LinkedHashMap<>(bearer);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }
}
