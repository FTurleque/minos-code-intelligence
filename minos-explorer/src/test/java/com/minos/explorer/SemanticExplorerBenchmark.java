package com.minos.explorer;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.application.semantic.SemanticIndexBudget;
import com.minos.application.semantic.SemanticProjectionService;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Benchmark of the semantic exploration: the preparation of a two-dimensional projection and every read route of
 * the exploration surface served over HTTP on the loopback, on synthetic indexes of declared sizes and dimension,
 * on the local store and on PostgreSQL. It is a test-source main class whose name matches no Surefire inclusion
 * pattern: it never runs in {@code mvn verify}; {@link SemanticExplorerBenchmarkSmokeTest} runs it on a tiny
 * index. It is launched by {@code benchmarks/scalability/run-semantic-benchmark.ps1}.
 *
 * <p>Every dataset is synthetic: documents and vectors come from a fixed seed and the deterministic local-hash
 * provider, and are written directly through the write port of the semantic vector store, outside the
 * synchronization. A dataset that the synchronization would refuse to build carries the
 * {@code beyond-build-budget} mention in its label.</p>
 *
 * <p>Output on the standard output, one line per value: {@code METRIC\tsection\tdataset\tname\tvalue}, or
 * {@code REFUSED\tsection\tdataset\treason} for a measure that could not be taken; a reason carries neither an
 * absolute path nor a secret. The exit code is 0 when every refusal was declared expected and every expected
 * refusal happened, 1 otherwise, and 2 for a usage error.</p>
 */
final class SemanticExplorerBenchmark {

    /** The measured scenarios of each route of the surface; the smoke test checks that no route is missing. */
    static final Map<ExplorerApi.Route, List<String>> MEASURED_ROUTES;

    static {
        Map<ExplorerApi.Route, List<String>> routes = new EnumMap<>(ExplorerApi.Route.class);
        routes.put(ExplorerApi.Route.PROJECTS, List.of("projects"));
        routes.put(ExplorerApi.Route.DOCUMENTS, List.of("list-first", "list-deep"));
        routes.put(ExplorerApi.Route.DOCUMENT, List.of("detail"));
        routes.put(ExplorerApi.Route.NEIGHBORS, List.of("neighbors"));
        routes.put(ExplorerApi.Route.PROJECTION, List.of("projection-status"));
        routes.put(ExplorerApi.Route.PROJECTION_POINTS, List.of("projection-points"));
        routes.put(ExplorerApi.Route.SEARCH, List.of("search"));
        MEASURED_ROUTES = Collections.unmodifiableMap(routes);
    }

    static final String BEYOND_BUILD_BUDGET = "beyond-build-budget";

    private static final String SNAPSHOT = "snapshot-benchmark";
    private static final String SEARCH_QUERY = "resolve the snapshot vector of a registry";
    private static final int NEIGHBORS_LIMIT = 20;
    private static final String[] VERBS = {"read", "write", "parse", "index", "resolve", "render", "store", "load"};
    private static final String[] NOUNS = {"symbol", "snapshot", "vector", "project", "module", "document",
            "provider", "registry", "sandbox", "relation", "occurrence", "manifest"};
    private static final Pattern NEXT_CURSOR = Pattern.compile("\"nextCursor\":\"([^\"]+)\"");
    private static final Pattern TOTAL = Pattern.compile("\"total\":([0-9]+)");
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\":\"([A-Z_]+)\"");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_]{1,24}");

    private SemanticExplorerBenchmark() {
    }

    public static void main(String[] arguments) {
        System.exit(run(Arrays.asList(arguments), System.out, System.err, UnaryOperator.identity()));
    }

    /**
     * Runs the benchmark. {@code calls} wraps what answers the API of each served surface; it is the identity
     * outside the smoke test.
     */
    static int run(List<String> arguments, PrintStream out, PrintStream err, UnaryOperator<ExplorerServer.Calls> calls) {
        Options options;
        try {
            options = Options.parse(arguments);
            requireNoGlobalStorageSetting();
            Files.createDirectories(options.work());
        } catch (IllegalArgumentException | IOException usage) {
            err.println("usage error: " + PublicErrorMessages.sanitize(usage.getMessage(), "invalid arguments"));
            return 2;
        }
        boolean complete = true;
        for (Dataset dataset : options.datasets()) {
            complete &= new DatasetRun(options, dataset, new Emitter(out), err, calls).run();
        }
        return complete ? 0 : 1;
    }

    /**
     * A storage setting given to the whole JVM would override the configuration of each scratch home, and a
     * local dataset could be written to a server: such a setting is refused before anything is measured.
     */
    private static void requireNoGlobalStorageSetting() {
        String[][] settings = {
                {StorageBackendConfiguration.BACKEND_PROPERTY, StorageBackendConfiguration.BACKEND_ENV},
                {StorageBackendConfiguration.POSTGRES_URL_PROPERTY, StorageBackendConfiguration.POSTGRES_URL_ENV},
                {StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, StorageBackendConfiguration.POSTGRES_SCHEMA_ENV},
                {StorageBackendConfiguration.POSTGRES_MANAGED_PROPERTY, StorageBackendConfiguration.POSTGRES_MANAGED_ENV}};
        for (String[] setting : settings) {
            if (!blank(System.getProperty(setting[0])) || !blank(System.getenv(setting[1]))) {
                throw new IllegalArgumentException("unset " + setting[1] + " and " + setting[0]
                        + ": the benchmark configures the storage of each scratch home itself");
            }
        }
    }

    /** One dataset, measured from a scratch home of its own. */
    private static final class DatasetRun {
        private final Options options;
        private final Dataset dataset;
        private final Emitter emit;
        private final PrintStream err;
        private final UnaryOperator<ExplorerServer.Calls> calls;
        private final LocalHashEmbeddingProvider provider;
        private String label;
        private boolean complete = true;

        DatasetRun(Options options, Dataset dataset, Emitter emit, PrintStream err,
                   UnaryOperator<ExplorerServer.Calls> calls) {
            this.options = options;
            this.dataset = dataset;
            this.emit = emit;
            this.err = err;
            this.calls = calls;
            this.provider = new LocalHashEmbeddingProvider(options.dimensions());
        }

        boolean run() {
            List<String[]> contents = contents();
            boolean beyond = beyondBuildBudget(contents);
            label = dataset.storage() + "-n" + dataset.documents() + "-d" + options.dimensions()
                    + (options.contentPaddingChars() > 0 ? "-c" + options.contentPaddingChars() : "")
                    + (beyond ? "-" + BEYOND_BUILD_BUDGET : "");
            String missing = missingPostgresqlParameter();
            if (missing != null) {
                refuseAll(missing);
                return false;
            }
            Path base = options.work().resolve(options.runId()).resolve(label);
            if (Files.exists(base)) {
                refuseAll("the work directory already holds this dataset for this run id; use a new run id");
                return false;
            }
            MinosApplication application;
            String projectId;
            try {
                Path home = base.resolve("home");
                Path root = Files.createDirectories(base.resolve("project"));
                if (dataset.postgresql()) writePostgresqlConfiguration(home);
                projectId = register(home, root);
                application = MinosApplication.builder(home).embeddingProvider(provider).build();
            } catch (Exception refused) {
                refuseAll("dataset not written: " + reason(refused));
                return false;
            }
            try (application) {
                if (!write(application, projectId, contents, beyond)) return false;
                contents = null;
                prepare(application, projectId);
                explore(application, projectId);
            } catch (Exception failure) {
                emit.refused("explorer", label, "the dataset could not be measured to its end: " + reason(failure));
                complete = false;
            }
            return complete;
        }

        private String missingPostgresqlParameter() {
            if (!dataset.postgresql()) return null;
            if (blank(options.postgresUrl())) return "missing parameter --postgres-url";
            if (blank(options.postgresUser())) return "missing parameter --postgres-user";
            if (options.postgresPasswordFile() == null
                    && blank(System.getenv(StorageBackendConfiguration.POSTGRES_PASSWORD_ENV))) {
                // Written without the words a public message may not carry, or the sanitizer would hide it.
                return "missing PostgreSQL credential: neither its file argument nor its environment variable "
                        + "is given (see the README of the benchmark)";
            }
            return null;
        }

        private void refuseAll(String reason) {
            for (String section : List.of("dataset", "projection", "explorer", "heap")) {
                emit.refused(section, label, reason);
            }
            complete = false;
        }

        /**
         * The configuration of a PostgreSQL scratch home: one schema per run and dataset, so that two runs never
         * share data. The password stays in the environment of the JVM or in the operator's restricted file.
         */
        private void writePostgresqlConfiguration(Path home) throws IOException {
            Properties settings = new Properties();
            settings.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
            settings.setProperty(StorageBackendConfiguration.POSTGRES_URL_PROPERTY, options.postgresUrl());
            settings.setProperty(StorageBackendConfiguration.POSTGRES_USER_PROPERTY, options.postgresUser());
            settings.setProperty(StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, ("mb_" + options.runId() + "_n"
                    + dataset.documents() + "_d" + options.dimensions()).toLowerCase(Locale.ROOT));
            if (options.postgresPasswordFile() != null) {
                settings.setProperty(StorageBackendConfiguration.POSTGRES_PASSWORD_FILE_PROPERTY,
                        options.postgresPasswordFile().toString());
            }
            Path configuration = Files.createDirectories(home.resolve("config")).resolve("minos.properties");
            try (OutputStream output = Files.newOutputStream(configuration)) {
                settings.store(output, "scratch home of the semantic benchmark");
            }
        }

        private String register(Path home, Path root) throws IOException {
            try (MinosApplication baseline = MinosApplication.open(home)) {
                if (!baseline.projectRegistry().listProjects().isEmpty()) {
                    throw new IllegalStateException("the storage of this scratch home already holds a project; "
                            + "use an empty database");
                }
                var project = baseline.projectRegistry().registerProject(root, "semantic-benchmark");
                String projectId = project.id().toString();
                baseline.snapshotStore().publish(project.id(), SNAPSHOT, List.of(symbol(projectId)), List.of(),
                        List.of());
                return projectId;
            }
        }

        /** Stable keys and contents, drawn from the seed: the same configuration gives the same index. */
        private List<String[]> contents() {
            SplittableRandom random = new SplittableRandom(options.seed());
            List<String[]> contents = new ArrayList<>(dataset.documents());
            for (int number = 0; number < dataset.documents(); number++) {
                String noun = NOUNS[random.nextInt(NOUNS.length)];
                String key = String.format(Locale.ROOT, "chunk:synthetic:src/%s/File%06d.java:%d", noun, number,
                        random.nextInt(400));
                String content = "void " + VERBS[random.nextInt(VERBS.length)] + capital(noun) + random.nextInt(97)
                        + "() { " + NOUNS[random.nextInt(NOUNS.length)] + "." + VERBS[random.nextInt(VERBS.length)]
                        + "(" + NOUNS[random.nextInt(NOUNS.length)] + "); }";
                contents.add(new String[] {key, padded(content, options.contentPaddingChars())});
            }
            return contents;
        }

        /** The content repeated up to {@code chars} characters, so that a document weighs what a real chunk does. */
        private static String padded(String content, int chars) {
            if (chars <= content.length()) return content;
            StringBuilder padded = new StringBuilder(chars);
            while (padded.length() < chars) padded.append(content).append(' ');
            return padded.substring(0, chars);
        }

        /** Whether the synchronization of the product would refuse to build this index, by its own budget. */
        private boolean beyondBuildBudget(List<String[]> contents) {
            SemanticIndexBudget.Tracker tracker = SemanticIndexBudget.DEFAULT.tracker(options.dimensions());
            try {
                for (int number = 0; number < contents.size(); number++) {
                    tracker.account(document("budget", number, contents.get(number)));
                }
                return false;
            } catch (IOException overBudget) {
                return true;
            }
        }

        private boolean write(MinosApplication application, String projectId, List<String[]> contents,
                              boolean beyond) {
            try {
                long started = System.nanoTime();
                List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>(contents.size());
                for (int number = 0; number < contents.size(); number++) {
                    String[] content = contents.get(number);
                    indexed.add(new SemanticVectorStore.IndexedDocument(document(projectId, number, content),
                            provider.embed(content[0], content[1])));
                }
                application.semanticVectorStore().replace(new SemanticVectorStore.IndexSnapshot(projectId, SNAPSHOT,
                        provider.id(), provider.modelId(), options.dimensions(), 1L, indexed));
                long millis = (System.nanoTime() - started) / 1_000_000L;
                emit.metric("dataset", label, "documents", dataset.documents());
                emit.metric("dataset", label, "dimensions", options.dimensions());
                emit.metric("dataset", label, "writeMillis", millis);
                emit.metric("dataset", label, "beyondBuildBudget", beyond ? 1 : 0);
                return true;
            } catch (Exception refused) {
                refuseAll("dataset not written: " + reason(refused));
                return false;
            }
        }

        private void prepare(MinosApplication application, String projectId) {
            long[] pages = {0L};
            Map<Integer, Long> readByPass = new LinkedHashMap<>();
            SemanticProjectionService.Progress progress = (phase, pass, vectorsRead) -> {
                // A step on the in-memory copy of the sample announces itself without reading a vector.
                if (SemanticProjectionService.PUBLISH_PHASE.equals(phase) || vectorsRead == 0L) return;
                pages[0]++;
                readByPass.merge(pass, vectorsRead, Math::max);
            };
            try {
                SemanticProjectionService.PreparationReport report = application.semanticProjectionService()
                        .prepare(projectId, new SemanticProjectionService.PreparationRequest(options.maxPoints(),
                                options.seed(), EnumSet.allOf(SemanticDocumentKind.class),
                                options.maxMemoryBytes()), progress);
                emit.metric("projection", label, "documents", report.documentCount());
                emit.metric("projection", label, "durationMillis", report.durationMillis());
                emit.metric("projection", label, "passes", report.passes());
                emit.metric("projection", label, "iterations", report.iterations());
                emit.metric("projection", label, "inMemory",
                        report.strategy() == SemanticProjectionService.Strategy.IN_MEMORY ? 1 : 0);
                emit.metric("projection", label, "vectorPages", pages[0]);
                emit.metric("projection", label, "vectorsRead",
                        readByPass.values().stream().mapToLong(Long::longValue).sum());
                emit.metric("projection", label, "peakWorkingBytes", report.peakWorkingBytes());
                emit.metric("projection", label, "pointCount", report.pointCount());
                emit.metric("projection", label, "candidateCount", report.candidateCount());
                emit.metric("projection", label, "sampled", report.sampled() ? 1 : 0);
            } catch (SemanticProjectionService.RefusedException refused) {
                emit.refused("projection", label, refused.refusal().name() + ": "
                        + PublicErrorMessages.sanitize(refused.getMessage(), PublicErrorMessages.REDACTED_DETAIL));
                complete = false;
            } catch (Exception failure) {
                emit.refused("projection", label, reason(failure));
                complete = false;
            }
        }

        private void explore(MinosApplication application, String projectId) throws IOException {
            ExplorerApi api = new ExplorerApi(application.semanticCorpusQueryService(),
                    application.semanticProjectionService());
            try (ExplorerServer server = ExplorerServer.start(calls.apply(api::answer), 0,
                    ListeningPolicy.Scope.LOOPBACK, Duration.ofSeconds(options.requestTimeoutSeconds()),
                    message -> err.println("explorer: " + message))) {
                Client client = new Client(server, Duration.ofSeconds(options.requestTimeoutSeconds()));
                String stableKey = contentsKey(dataset.documents() / 2);
                List<String> project = List.of("projects", projectId);
                Map<String, Request> requests = new LinkedHashMap<>();
                requests.put("projects", Request.get(List.of("projects"), Map.of()));
                requests.put("list-first", Request.get(concat(project, "documents"), Map.of()));
                requests.put("list-deep", null);
                requests.put("detail", Request.get(concat(project, "documents", stableKey), Map.of()));
                requests.put("neighbors", Request.get(concat(project, "documents", stableKey, "neighbors"),
                        Map.of("limit", Integer.toString(NEIGHBORS_LIMIT))));
                requests.put("projection-status", Request.get(concat(project, "projection"), Map.of()));
                requests.put("projection-points", Request.get(concat(project, "projection", "points"),
                        Map.of("offset", "0", "limit", Integer.toString(SemanticProjectionService.MAX_POINTS_PAGE))));
                requests.put("search", new Request("POST", concat(project, "search"), Map.of(),
                        "{\"query\":\"" + SEARCH_QUERY + "\",\"mode\":\"semantic\",\"limit\":20}"));

                Set<ExplorerApi.Route> succeededUnderExpectation = new LinkedHashSet<>();
                emit.metric("heap", label, "retainedBeforeFirstCallBytes", retainedHeap());
                for (Map.Entry<String, Request> entry : requests.entrySet()) {
                    String scenario = entry.getKey();
                    ExplorerApi.Route route = routeOf(scenario);
                    Outcome outcome;
                    if (scenario.equals("list-deep")) {
                        DeepPage deep = deepPage(client, api, project);
                        outcome = deep.refusal() != null ? Outcome.refused(deep.refusal())
                                : measure(client, api, deep.request());
                    } else {
                        outcome = measure(client, api, entry.getValue());
                    }
                    boolean expected = dataset.expectedRefusals().contains(route);
                    if (outcome.refusal() != null) {
                        emit.refused("explorer", label, scenario + ": " + outcome.refusal());
                        if (!expected) complete = false;
                    } else {
                        outcome.emit(emit, label, scenario);
                        if (expected) succeededUnderExpectation.add(route);
                    }
                    if (scenario.equals("list-deep")) {
                        emit.metric("heap", label, "retainedAfterListBytes", retainedHeap());
                    } else if (scenario.equals("neighbors")) {
                        emit.metric("heap", label, "retainedAfterNeighborsBytes", retainedHeap());
                    }
                }
                readAllPoints(client, project);
                emit.metric("heap", label, "retainedAfterAllPointsBytes", retainedHeap());
                for (ExplorerApi.Route route : succeededUnderExpectation) {
                    err.println("the expected refusal of " + route + " did not happen: remove " + route
                            + " from the expected refusals of " + label);
                    complete = false;
                }
            }
        }

        /**
         * The request of the deep page, reached by following the cursors; when a page on the way is refused, the
         * refusal carries the answer of the surface and the motive of the product for that page.
         */
        private DeepPage deepPage(Client client, ExplorerApi api, List<String> project) throws IOException {
            String cursor = null;
            for (int page = 1; page < options.deepPage(); page++) {
                Map<String, String> parameters = cursor == null ? Map.of() : Map.of("cursor", cursor);
                Request request = Request.get(concat(project, "documents"), parameters);
                Client.Response response = client.send(request);
                if (response.status() != 200) {
                    return new DeepPage(null, "page " + page + " on the way to the deep page was refused, "
                            + refusal(response, api, request));
                }
                Matcher next = NEXT_CURSOR.matcher(response.body());
                if (!next.find()) break;
                cursor = next.group(1);
            }
            return new DeepPage(Request.get(concat(project, "documents"),
                    cursor == null ? Map.of() : Map.of("cursor", cursor)), null);
        }

        private void readAllPoints(Client client, List<String> project) throws IOException {
            int offset = 0;
            while (true) {
                Client.Response response = client.send(Request.get(concat(project, "projection", "points"),
                        Map.of("offset", Integer.toString(offset),
                                "limit", Integer.toString(SemanticProjectionService.MAX_POINTS_PAGE))));
                Matcher total = TOTAL.matcher(response.body());
                if (response.status() != 200 || !total.find()) return;
                offset += SemanticProjectionService.MAX_POINTS_PAGE;
                if (offset >= Long.parseLong(total.group(1))) return;
            }
        }

        /** A cold call, the warm-up, then the measured iterations of one request. */
        private Outcome measure(Client client, ExplorerApi api, Request request) throws IOException {
            long started = System.nanoTime();
            Client.Response cold = client.send(request);
            long coldNanos = System.nanoTime() - started;
            if (cold.status() != 200) return Outcome.refused(refusal(cold, api, request));
            for (int warm = 0; warm < options.warmup(); warm++) {
                Client.Response response = client.send(request);
                if (response.status() != 200) return Outcome.refused("during the warm-up, " + refusal(response, api, request));
            }
            long[] nanos = new long[options.iterations()];
            long[] allocated = new long[options.iterations()];
            Client.Response last = cold;
            for (int iteration = 0; iteration < options.iterations(); iteration++) {
                long allocatedBefore = allocatedBytes();
                started = System.nanoTime();
                last = client.send(request);
                nanos[iteration] = System.nanoTime() - started;
                allocated[iteration] = allocatedBytes() - allocatedBefore;
                if (last.status() != 200) return Outcome.refused("during the iterations, " + refusal(last, api, request));
            }
            Arrays.sort(nanos);
            Arrays.sort(allocated);
            return new Outcome(null, coldNanos / 1_000L, percentile(nanos, 50) / 1_000L, percentile(nanos, 95) / 1_000L,
                    nanos[nanos.length - 1] / 1_000L, percentile(allocated, 50),
                    last.body().getBytes(StandardCharsets.UTF_8).length);
        }

        /**
         * The status and code the surface answered, and the motive of the product: the same request is answered
         * once more by the API itself, outside HTTP, and the failure it reports is named (sanitized).
         */
        private String refusal(Client.Response response, ExplorerApi api, Request request) {
            Matcher code = ERROR_CODE.matcher(response.body());
            StringBuilder reason = new StringBuilder("http ").append(response.status())
                    .append(code.find() ? " " + code.group(1) : "");
            List<Exception> failures = new ArrayList<>();
            api.answer(request.method(), request.segments(), request.parameters(), request::bodyText, failure -> {
                failures.add(failure);
                return "benchmark";
            });
            if (!failures.isEmpty()) reason.append("; ").append(reason(failures.getFirst()));
            return reason.toString();
        }

        private String contentsKey(int number) {
            // The keys are drawn again from the seed: the contents list is released once the index is written.
            List<String[]> contents = contents();
            return contents.get(Math.min(number, contents.size() - 1))[0];
        }

        private static ExplorerApi.Route routeOf(String scenario) {
            for (Map.Entry<ExplorerApi.Route, List<String>> entry : MEASURED_ROUTES.entrySet()) {
                if (entry.getValue().contains(scenario)) return entry.getKey();
            }
            throw new IllegalStateException("scenario without route: " + scenario);
        }
    }

    /** The request of the deep page of the list, or the reason why it could not be reached. */
    private record DeepPage(Request request, String refusal) {
    }

    /** The statistics of one measured scenario, or the reason why it could not be measured. */
    private record Outcome(String refusal, long coldMicros, long p50Micros, long p95Micros, long maxMicros,
                           long allocatedBytesPerRequest, long responseBytes) {
        static Outcome refused(String reason) {
            return new Outcome(reason, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        void emit(Emitter emit, String label, String scenario) {
            emit.metric("explorer", label, scenario + ".coldMicros", coldMicros);
            emit.metric("explorer", label, scenario + ".p50Micros", p50Micros);
            emit.metric("explorer", label, scenario + ".p95Micros", p95Micros);
            emit.metric("explorer", label, scenario + ".maxMicros", maxMicros);
            emit.metric("explorer", label, scenario + ".allocatedBytesPerRequest", allocatedBytesPerRequest);
            emit.metric("explorer", label, scenario + ".responseBytes", responseBytes);
        }
    }

    /** One API request: method, path segments after the version prefix, query parameters and body. */
    private record Request(String method, List<String> segments, Map<String, String> parameters, String body) {
        static Request get(List<String> segments, Map<String, String> parameters) {
            return new Request("GET", segments, parameters, null);
        }

        String bodyText() {
            return body == null ? "" : body;
        }

        String path() {
            StringBuilder path = new StringBuilder(ExplorerContract.API_PREFIX);
            for (int index = 0; index < segments.size(); index++) {
                if (index > 0) path.append('/');
                path.append(URLEncoder.encode(segments.get(index), StandardCharsets.UTF_8));
            }
            String separator = "?";
            for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                path.append(separator).append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
                separator = "&";
            }
            return path.toString();
        }
    }

    /** An HTTP/1.1 client of one served surface, with its session token, over kept-alive connections. */
    private static final class Client {
        private final HttpClient http;
        private final String origin;
        private final String bearer;
        private final Duration timeout;

        Client(ExplorerServer server, Duration timeout) {
            this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
            this.origin = "http://127.0.0.1:" + server.port();
            String url = server.accessUrl();
            this.bearer = "Bearer " + url.substring(url.indexOf("#token=") + "#token=".length());
            this.timeout = timeout;
        }

        record Response(int status, String body) {
        }

        Response send(Request request) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + request.path()))
                    .timeout(timeout).header("Authorization", bearer);
            if (request.body() == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
            }
            try {
                HttpResponse<String> response = http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Response(response.statusCode(), response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the surface", interrupted);
            }
        }
    }

    /** Writes the result lines; a reason is flattened and stripped of paths and secrets before it is written. */
    private static final class Emitter {
        private final PrintStream out;

        Emitter(PrintStream out) {
            this.out = out;
        }

        void metric(String section, String dataset, String name, long value) {
            out.println("METRIC\t" + section + "\t" + dataset + "\t" + name + "\t" + value);
        }

        void refused(String section, String dataset, String reason) {
            out.println("REFUSED\t" + section + "\t" + dataset + "\t"
                    + PublicErrorMessages.sanitize(reason, PublicErrorMessages.REDACTED_DETAIL));
        }
    }

    /** A dataset of the configuration: storage, size and the routes expected to be refused. */
    record Dataset(String storage, int documents, Set<ExplorerApi.Route> expectedRefusals) {
        boolean postgresql() {
            return storage.equals("postgresql");
        }

        /** {@code storage:documents[:expect-refusal=ROUTE+ROUTE]}. */
        static Dataset parse(String value) {
            String[] parts = value.split(":", -1);
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException("a dataset is storage:documents[:expect-refusal=ROUTE+ROUTE]");
            }
            String storage = parts[0];
            if (!storage.equals("local") && !storage.equals("postgresql")) {
                throw new IllegalArgumentException("a dataset storage is local or postgresql");
            }
            int documents = positive(parts[1], "documents");
            Set<ExplorerApi.Route> refusals = EnumSet.noneOf(ExplorerApi.Route.class);
            if (parts.length == 3) {
                if (!parts[2].startsWith("expect-refusal=")) {
                    throw new IllegalArgumentException("unknown dataset option; expect-refusal=ROUTE+ROUTE");
                }
                for (String route : parts[2].substring("expect-refusal=".length()).split("\\+")) {
                    try {
                        refusals.add(ExplorerApi.Route.valueOf(route));
                    } catch (IllegalArgumentException unknown) {
                        throw new IllegalArgumentException("unknown route in expect-refusal: " + route);
                    }
                }
            }
            return new Dataset(storage, documents, refusals);
        }
    }

    /** The arguments of a run. */
    record Options(Path work, List<Dataset> datasets, int dimensions, long seed, int warmup, int iterations,
                   int maxPoints, long maxMemoryBytes, int deepPage, int requestTimeoutSeconds, String runId,
                   int contentPaddingChars, String postgresUrl, String postgresUser, Path postgresPasswordFile) {

        static Options parse(List<String> arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            List<Dataset> datasets = new ArrayList<>();
            for (String argument : arguments) {
                int equals = argument.indexOf('=');
                if (!argument.startsWith("--") || equals < 0) {
                    throw new IllegalArgumentException("arguments are --name=value");
                }
                String name = argument.substring(2, equals);
                String value = argument.substring(equals + 1);
                if (name.equals("dataset")) {
                    datasets.add(Dataset.parse(value));
                } else if (values.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("argument given twice: --" + name);
                }
            }
            Set<String> known = Set.of("work", "dimensions", "seed", "warmup", "iterations", "max-points",
                    "max-memory-bytes", "deep-page", "request-timeout-seconds", "run-id", "content-padding-chars",
                    "postgres-url",
                    "postgres-user", "postgres-password-file");
            for (String name : values.keySet()) {
                if (!known.contains(name)) throw new IllegalArgumentException("unknown argument: --" + name);
            }
            if (datasets.isEmpty()) throw new IllegalArgumentException("at least one --dataset is required");
            String work = values.get("work");
            if (blank(work)) throw new IllegalArgumentException("--work is required");
            String runId = values.getOrDefault("run-id",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)));
            if (!RUN_ID.matcher(runId).matches()) {
                throw new IllegalArgumentException("--run-id is 1 to 24 letters, digits or underscores");
            }
            String passwordFile = values.get("postgres-password-file");
            return new Options(Path.of(work).toAbsolutePath().normalize(), List.copyOf(datasets),
                    positive(values.getOrDefault("dimensions", "768"), "dimensions"),
                    Long.parseLong(values.getOrDefault("seed", "42")),
                    nonNegative(values.getOrDefault("warmup", "5"), "warmup"),
                    positive(values.getOrDefault("iterations", "20"), "iterations"),
                    positive(values.getOrDefault("max-points",
                            Integer.toString(SemanticProjectionService.DEFAULT_MAX_POINTS)), "max-points"),
                    Long.parseLong(values.getOrDefault("max-memory-bytes",
                            Long.toString(SemanticProjectionService.DEFAULT_MAX_MEMORY_MEGABYTES * 1024L * 1024L))),
                    positive(values.getOrDefault("deep-page", "10"), "deep-page"),
                    positive(values.getOrDefault("request-timeout-seconds", "120"), "request-timeout-seconds"),
                    runId, nonNegative(values.getOrDefault("content-padding-chars", "0"), "content-padding-chars"),
                    values.get("postgres-url"), values.get("postgres-user"),
                    blank(passwordFile) ? null : Path.of(passwordFile).toAbsolutePath().normalize());
        }
    }

    private static SemanticDocument document(String projectId, int number, String[] content) {
        return new SemanticDocument("id-" + number, content[0], projectId, SNAPSHOT, SemanticDocumentKind.CHUNK,
                "source-" + number, "src/File" + number + ".java", 1, 3, content[1], "chk-" + number);
    }

    private static Symbol symbol(String projectId) {
        return new Symbol("Benchmark", "benchmark:Benchmark", SymbolIdentityQuality.CANONICAL, projectId, "module-a",
                "src/Benchmark.java", null, SymbolKind.CLASS, "Benchmark", "benchmark.Benchmark", "class Benchmark",
                "java", new SymbolLocation("src/Benchmark.java", 1, 0, 3, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("benchmark", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    private static List<String> concat(List<String> head, String... tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(Arrays.asList(tail));
        return List.copyOf(all);
    }

    /** The class of a failure and its message, sanitized: no path, no secret, no control character. */
    private static String reason(Exception failure) {
        return failure.getClass().getSimpleName() + ": "
                + PublicErrorMessages.sanitize(failure.getMessage(), PublicErrorMessages.REDACTED_DETAIL);
    }

    /** Heap used once the collector has settled; an approximation of what the application retains. */
    private static long retainedHeap() {
        for (int round = 0; round < 3; round++) System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** Bytes allocated by every live thread of the JVM: the server, the client and the harness. */
    private static long allocatedBytes() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean threads
                && threads.isThreadAllocatedMemorySupported()) {
            if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
            return threads.getTotalThreadAllocatedBytes();
        }
        throw new IllegalStateException("this JVM does not count allocated bytes");
    }

    private static long percentile(long[] sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static int positive(String value, String name) {
        int parsed = nonNegative(value, name);
        if (parsed < 1) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private static int nonNegative(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new IllegalArgumentException(name + " must not be negative");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String capital(String word) {
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
}
