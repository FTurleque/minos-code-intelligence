package com.minos.explorer;

import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.io.InternalFailureJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The diagnostic lines of the surface: one writer, a reference for an internal failure, bounded repeated events. */
class ExplorerDiagnosticsTest {

    private static final Pattern REFERENCE = Pattern.compile("\\(ref ([0-9a-f]{8})\\)");

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final AtomicLong nanos = new AtomicLong();
    private ExplorerServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    // --- the writer, with an injected clock

    @Test
    void aBurstOfRefusalsOfOneReasonIsOneLineThenOneSummaryPerWindowAndOtherReasonsAreWrittenAtOnce() {
        ExplorerDiagnostics diagnostics = new ExplorerDiagnostics(log::add,
                InternalFailureReporter.withoutJournal("explorer"), nanos::get);

        for (int index = 0; index < 1_000; index++) diagnostics.refusal("token");

        assertEquals(List.of("refused request: token"), log);

        diagnostics.refusal("origin");
        assertEquals(List.of("refused request: token", "refused request: origin"), log);

        nanos.addAndGet(ExplorerDiagnostics.WINDOW.toNanos());
        diagnostics.refusal("token");
        assertEquals("refused requests: token (1000 since the previous line)", log.get(2));
        assertEquals(3, log.size());
    }

    @Test
    void aSingleRefusalAfterTheWindowIsWrittenInItsFirstForm() {
        ExplorerDiagnostics diagnostics = new ExplorerDiagnostics(log::add,
                InternalFailureReporter.withoutJournal("explorer"), nanos::get);

        diagnostics.refusal("host");
        nanos.addAndGet(ExplorerDiagnostics.WINDOW.toNanos() + 1);
        diagnostics.refusal("host");

        assertEquals(List.of("refused request: host", "refused request: host"), log);
    }

    @Test
    void abandonedReadsAreSaidWithTheirNumberAndTheSameLimitation() {
        ExplorerDiagnostics diagnostics = new ExplorerDiagnostics(log::add,
                InternalFailureReporter.withoutJournal("explorer"), nanos::get);

        for (int index = 0; index < 5; index++) diagnostics.abandonedReads(8, 8);
        nanos.addAndGet(ExplorerDiagnostics.WINDOW.toNanos());
        diagnostics.abandonedReads(8, 8);

        assertEquals(List.of("abandoned reads still running: 8 (limit 8)",
                "abandoned reads still running: 8 (limit 8); 5 requests refused since the previous line"), log);
    }

    @Test
    void failuresOfTheExchangeAreOneLineThenOneSummaryPerWindow() {
        ExplorerDiagnostics diagnostics = new ExplorerDiagnostics(log::add,
                InternalFailureReporter.withoutJournal("explorer"), nanos::get);

        for (int index = 0; index < 500; index++) diagnostics.exchangeFailure(new java.io.IOException("cut"));

        assertEquals(1, log.size(), log.toString());
        assertTrue(log.get(0).startsWith("request failed (route=serve, type=java.io.IOException"), log.get(0));

        nanos.addAndGet(ExplorerDiagnostics.WINDOW.toNanos());
        diagnostics.exchangeFailure(new java.io.IOException("cut"));

        assertEquals(3, log.size(), log.toString());
        assertEquals("request failures outside a route: 499 since the previous line", log.get(1));
        assertTrue(log.get(2).startsWith("request failed (route=serve, "), log.get(2));
    }

    // --- on real sockets

    @Test
    void aThousandRequestsWithoutTheTokenAreAllRefusedWithA401AndWriteOneLine() throws Exception {
        start((method, segments, parameters, body, failures) -> ExplorerApi.Answer.ok(Map.of()),
                InternalFailureReporter.withoutJournal("explorer"));

        for (int index = 0; index < 1_000; index++) {
            assertEquals(401, RawHttp.get(server.port(), "/api/v1/projects", Map.of()).status());
        }

        assertEquals(List.of("refused request: token"), log);
        assertFalse(log.toString().contains(token()), log.toString());
    }

    @Test
    void anInternalFailureAnswersA500WithAReferenceThatTheLineShares(@TempDir Path home) throws Exception {
        start((method, segments, parameters, body, failures) -> {
            throw new IllegalStateException("cannot read C:\\secret\\root", new NullPointerException("inner"));
        }, new InternalFailureReporter("explorer", home, Map.of(), new Properties()));

        RawHttp.Response failed = RawHttp.get(server.port(), "/api/v1/projects/p/documents/secret-key%2Fa.java",
                bearer());

        assertEquals(500, failed.status());
        Matcher reference = REFERENCE.matcher(failed.body());
        assertTrue(reference.find(), failed.body());
        assertEquals(1, log.size(), log.toString());
        String line = log.get(0);
        assertTrue(line.startsWith("request failed (route=document, type=java.lang.IllegalStateException, "
                + "rootCause=java.lang.NullPointerException, at=com.minos.explorer.ExplorerDiagnosticsTest."), line);
        assertTrue(line.endsWith("ref=" + reference.group(1) + ")"), line);
        for (String forbidden : new String[]{"secret", "C:\\", token(), "a.java"}) {
            assertFalse(line.contains(forbidden), forbidden + " in " + line);
            assertFalse(failed.body().contains(forbidden), forbidden + " in " + failed.body());
        }
    }

    @Test
    void aFailureOutsideAnApiRouteWritesTheSameShapeOfLine() throws Exception {
        start((method, segments, parameters, body, failures) -> new ExplorerApi.Answer(200, null, null),
                InternalFailureReporter.withoutJournal("explorer"));

        try {
            RawHttp.get(server.port(), "/api/v1/projects", bearer());
        } catch (IOException | IllegalStateException closedWithoutAnswer) {
            // The exchange is closed before the failure is written: the client sees no answer.
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (log.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();

        assertEquals(1, log.size(), log.toString());
        assertTrue(log.get(0).startsWith("request failed (route=serve, type=java.lang.NullPointerException, "
                + "rootCause=java.lang.NullPointerException, at="), log.get(0));
        assertTrue(log.get(0).matches(".*, ref=[0-9a-f]{8}\\)"), log.get(0));
    }

    @Test
    void withoutTheJournalSettingAnInternalFailureLeavesTheHomeAsItWas(@TempDir Path home) throws Exception {
        Files.writeString(Files.createDirectories(home.resolve("index")).resolve("data"), "x");
        List<String> before = listing(home);
        start(failing(), new InternalFailureReporter("explorer", home, Map.of(), new Properties()));

        RawHttp.get(server.port(), "/api/v1/projects", bearer());

        assertEquals(before, listing(home));
    }

    @Test
    void withTheJournalActiveOnlyAFailureAddsTheJournalDirectory(@TempDir Path home) throws Exception {
        Files.writeString(Files.createDirectories(home.resolve("index")).resolve("data"), "x");
        List<String> before = listing(home);
        start((method, segments, parameters, body, failures) -> {
            if (segments.contains("boom")) throw new IllegalStateException("boom");
            return ExplorerApi.Answer.ok(Map.of("ok", true));
        }, new InternalFailureReporter("explorer", home,
                Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled"), new Properties()));

        assertEquals(200, RawHttp.get(server.port(), "/api/v1/projects", bearer()).status());
        assertEquals(200, RawHttp.get(server.port(), "/api/v1/projects/p/documents", bearer()).status());
        assertEquals(before, listing(home), "successful consultations write nothing");

        RawHttp.Response failed = RawHttp.get(server.port(), "/api/v1/projects/boom/documents", bearer());

        assertEquals(500, failed.status());
        List<String> after = listing(home);
        assertEquals(before, after.stream().filter(path -> !path.startsWith("logs")).toList());
        assertTrue(after.stream().anyMatch(path -> path.startsWith("logs")), after.toString());
        Matcher reference = REFERENCE.matcher(failed.body());
        assertTrue(reference.find(), failed.body());
        try (Stream<Path> entries = Files.list(home.resolve("logs").resolve("internal-failures"))) {
            List<Path> files = entries.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().endsWith("-" + reference.group(1) + ".txt"));
            assertTrue(Files.readString(files.get(0)).contains("surface=explorer operation=documents"));
        }
    }

    // --- the classification of an argument error, on the API without HTTP

    @Test
    void anArgumentErrorThatIsNotAUsageErrorIsAnInternalFailureOfTheApi(@TempDir Path work) throws Exception {
        try (IndexedHome fixture = IndexedHome.create(work)) {
            ExplorerApi api = new ExplorerApi(fixture.application.semanticCorpusQueryService(),
                    fixture.application.semanticProjectionService());
            List<Exception> failures = new ArrayList<>();
            List<RuntimeException> defects = List.of(
                    new IllegalArgumentException("limit must be positive C:\\secret"),
                    new java.nio.file.InvalidPathException("a|b", "Illegal char"));

            for (RuntimeException defect : defects) {
                ExplorerApi.Answer answer = api.answer("POST", List.of("projects", fixture.projectId, "search"),
                        Map.of(), () -> {
                            throw defect;
                        }, failure -> {
                            failures.add(failure);
                            return "0123abcd";
                        });

                assertEquals(500, answer.status(), defect.getMessage());
                assertTrue(answer.json().contains("\"code\":\"INTERNAL_ERROR\""), answer.json());
                assertTrue(answer.json().contains("(ref 0123abcd)"), answer.json());
                assertFalse(answer.json().contains("secret") || answer.json().contains("positive"), answer.json());
            }
            assertEquals(2, failures.size());
        }
    }

    private void start(ExplorerServer.Calls calls, InternalFailureReporter reporter) throws IOException {
        server = ExplorerServer.start(calls, 0, ListeningPolicy.Scope.LOOPBACK, Duration.ofSeconds(5), log::add,
                reporter, nanos::get);
    }

    private static ExplorerServer.Calls failing() {
        return (method, segments, parameters, body, failures) -> {
            throw new IllegalStateException("boom");
        };
    }

    private String token() {
        String url = server.accessUrl();
        return url.substring(url.indexOf("#token=") + "#token=".length());
    }

    private Map<String, String> bearer() {
        return Map.of("Authorization", "Bearer " + token());
    }

    private static List<String> listing(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.map(path -> root.relativize(path).toString().replace('\\', '/')).sorted().toList();
        }
    }
}
