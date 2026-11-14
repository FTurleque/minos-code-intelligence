package com.minos.explorer;

import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.application.semantic.SemanticProjectionService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The HTTP listener of the exploration surface, on the HTTP server of the JDK. It serves the embedded page and
 * the read-only API to one person on one machine: every request passes {@link RequestGuard}, every API call is
 * bounded in size, in duration and in number, and nothing but a status leaves on a refusal.
 */
final class ExplorerServer implements AutoCloseable {

    static final int MAX_BODY_BYTES = 512 * 1024;
    static final int MAX_CONCURRENT_API_CALLS = 4;
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_REQUEST_READ_SECONDS = 10;
    static final int MAX_RESPONSE_WRITE_SECONDS = 60;

    /** Policy of the page: its own scripts, styles and requests, nothing inline and nothing from elsewhere. */
    static final String PAGE_CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    static final String DATA_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    private static final String API_ROOT = "/api/";
    /** The page and its two resources: the only reads served to a navigation coming from another site. */
    private static final Set<String> LINK_OPENED_PATHS = Set.of("/", "/app.css", "/app.js");
    private static final String REFUSED_PAGE_TEXT = """
            MINOS explorer : requête refusée.
            Ouvrez l'adresse imprimée par « minos explorer serve » en la collant dans la barre d'adresse \
            d'un navigateur de cette machine.
            """;
    private static final String JSON = "application/json; charset=utf-8";
    private static final int CONNECTION_THREADS = 8;
    private static final int CONNECTION_QUEUE = 64;
    private static final int BACKLOG = 16;

    private final List<HttpServer> listeners = new ArrayList<>();
    private final ExecutorService connections;
    private final ExecutorService workers;
    private final Semaphore apiCalls = new Semaphore(MAX_CONCURRENT_API_CALLS);
    private final Calls api;
    private final RequestGuard guard;
    private final SessionToken token;
    private final Duration requestTimeout;
    private final Consumer<String> log;
    private final int port;
    private boolean closed;

    /** What answers an accepted API request; in production {@link ExplorerApi#answer}. */
    @FunctionalInterface
    interface Calls {
        ExplorerApi.Answer answer(String method, List<String> segments, Map<String, String> parameters,
                                  ExplorerApi.Body body, Consumer<Exception> failures);
    }

    private ExplorerServer(Calls api, SessionToken token, HttpServer first, Duration requestTimeout,
                           Consumer<String> log) {
        this.api = api;
        this.token = token;
        this.port = first.getAddress().getPort();
        this.guard = RequestGuard.forPort(port, token);
        this.requestTimeout = requestTimeout;
        this.log = log;
        this.connections = new ThreadPoolExecutor(CONNECTION_THREADS, CONNECTION_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONNECTION_QUEUE), threads("minos-explorer-http"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.workers = new ThreadPoolExecutor(MAX_CONCURRENT_API_CALLS, MAX_CONCURRENT_API_CALLS, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_CONCURRENT_API_CALLS),
                threads("minos-explorer-read"));
    }

    /**
     * Binds and starts the surface. {@code requestedPort} is 0 for a free port chosen by the system. The first
     * address of the scope must bind; the IPv6 loopback, when the machine has none, is skipped and said so.
     */
    static ExplorerServer start(SemanticCorpusQueryService corpus, SemanticProjectionService projection,
                                int requestedPort, ListeningPolicy.Scope scope, Duration requestTimeout,
                                Consumer<String> log) throws IOException {
        return start(new ExplorerApi(corpus, projection)::answer, requestedPort, scope, requestTimeout, log);
    }

    static ExplorerServer start(Calls api, int requestedPort, ListeningPolicy.Scope scope,
                                Duration requestTimeout, Consumer<String> log) throws IOException {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(log, "log");
        boundConnectionTimes();
        List<InetAddress> addresses = ListeningPolicy.addresses(scope);
        HttpServer first = HttpServer.create(new InetSocketAddress(addresses.getFirst(), requestedPort), BACKLOG);
        ExplorerServer server = new ExplorerServer(api, SessionToken.generate(), first,
                Objects.requireNonNull(requestTimeout, "requestTimeout"), log);
        server.listen(first);
        for (InetAddress address : addresses.subList(1, addresses.size())) {
            if (!ListeningPolicy.hostHasAddress(address)) {
                log.accept("the exploration surface does not listen on " + address.getHostAddress()
                        + " (this machine has no such address); use the 127.0.0.1 address");
                continue;
            }
            try {
                server.listen(HttpServer.create(new InetSocketAddress(address, server.port), BACKLOG));
            } catch (IOException taken) {
                // The machine has this loopback address and the port is not ours there: whoever holds it would
                // answer under the name localhost. The surface does not start beside it.
                server.close();
                throw taken;
            }
        }
        return server;
    }

    /**
     * Bounds, for the HTTP server of the JDK, the time a client may take to send a request and to read its
     * answer: without it, a few connections that send one byte and wait would hold every connection thread.
     * The server reads these system properties once per JVM; a value already set is kept.
     */
    private static void boundConnectionTimes() {
        setIfAbsent("sun.net.httpserver.maxReqTime", Integer.toString(MAX_REQUEST_READ_SECONDS));
        setIfAbsent("sun.net.httpserver.maxRspTime", Integer.toString(MAX_RESPONSE_WRITE_SECONDS));
    }

    private static void setIfAbsent(String property, String value) {
        if (System.getProperty(property) == null) System.setProperty(property, value);
    }

    private void listen(HttpServer listener) {
        listener.createContext("/", this::serve);
        listener.setExecutor(connections);
        listener.start();
        listeners.add(listener);
    }

    int port() {
        return port;
    }

    /** The addresses the surface is bound to. */
    List<InetSocketAddress> boundAddresses() {
        return listeners.stream().map(HttpServer::getAddress).toList();
    }

    /**
     * The address to open in a browser. The token travels in the fragment, which a browser keeps to itself: it
     * is neither sent to a server nor written in a {@code Referer}.
     */
    String accessUrl() {
        return "http://127.0.0.1:" + port + "/#token=" + token.value();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (HttpServer listener : listeners) listener.stop(0);
        connections.shutdownNow();
        workers.shutdownNow();
    }

    private void serve(HttpExchange exchange) {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            boolean apiRequest = path.startsWith(API_ROOT);
            Headers request = exchange.getRequestHeaders();
            Optional<RequestGuard.Refusal> refusal = guard.check(targetOf(exchange, path), request.getFirst("Host"),
                    request.getFirst("Origin"), request.getFirst("Sec-Fetch-Site"), request.getFirst("Authorization"));
            if (refusal.isPresent()) {
                log.accept("refused request: " + refusal.orElseThrow().name().toLowerCase(Locale.ROOT));
                refuse(exchange, refusal.orElseThrow().status(), apiRequest);
            } else if (apiRequest) {
                answer(exchange, path);
            } else {
                page(exchange, path);
            }
        } catch (IOException | RuntimeException failure) {
            // The exchange is closed by the try-with-resources; nothing of the failure reaches the client.
            log.accept("request failed: " + failure.getClass().getSimpleName());
        }
    }

    private static RequestGuard.Target targetOf(HttpExchange exchange, String path) {
        if (path.startsWith(API_ROOT)) return RequestGuard.Target.API;
        boolean pageRead = "GET".equals(exchange.getRequestMethod()) && LINK_OPENED_PATHS.contains(path);
        return pageRead ? RequestGuard.Target.PAGE_READ : RequestGuard.Target.OTHER;
    }

    /**
     * Answers a refused request. Outside the API the answer is read by a person in a browser: it carries one
     * fixed text that says how to open the surface, the same for every refusal. An API refusal has no body.
     */
    private static void refuse(HttpExchange exchange, int status, boolean apiRequest) throws IOException {
        if (apiRequest) {
            send(exchange, status, null, new byte[0], DATA_CONTENT_SECURITY_POLICY, null);
        } else {
            send(exchange, status, "text/plain; charset=utf-8", REFUSED_PAGE_TEXT.getBytes(StandardCharsets.UTF_8),
                    DATA_CONTENT_SECURITY_POLICY, null);
        }
    }

    private void page(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            send(exchange, 405, null, new byte[0], DATA_CONTENT_SECURITY_POLICY, "GET, HEAD");
            return;
        }
        Optional<WebResources.Resource> resource = WebResources.find(path);
        if (resource.isEmpty()) {
            send(exchange, 404, null, new byte[0], DATA_CONTENT_SECURITY_POLICY, null);
            return;
        }
        byte[] content = "HEAD".equals(method) ? new byte[0] : resource.orElseThrow().content();
        send(exchange, 200, resource.orElseThrow().mediaType(), content, PAGE_CONTENT_SECURITY_POLICY, null);
    }

    private void answer(HttpExchange exchange, String path) throws IOException {
        ExplorerApi.Answer answer = call(exchange, path);
        send(exchange, answer.status(), JSON, answer.json().getBytes(StandardCharsets.UTF_8),
                DATA_CONTENT_SECURITY_POLICY, answer.allow());
    }

    private ExplorerApi.Answer call(HttpExchange exchange, String path) {
        String method = exchange.getRequestMethod();
        List<String> segments;
        Map<String, String> parameters;
        try {
            if (!path.startsWith(ExplorerContract.API_PREFIX)) {
                return ExplorerApi.Answer.error(404, "NOT_FOUND", "the API lives under " + ExplorerContract.API_PREFIX);
            }
            segments = segments(path.substring(ExplorerContract.API_PREFIX.length()));
            parameters = parameters(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException malformed) {
            return ExplorerApi.Answer.error(400, "INVALID_REQUEST", "the request address is not valid");
        }
        if ("POST".equals(method)) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                return ExplorerApi.Answer.error(415, "UNSUPPORTED_MEDIA_TYPE", "the body must be application/json");
            }
        }
        if (!apiCalls.tryAcquire()) {
            return ExplorerApi.Answer.error(503, "BUSY", "too many requests at once; retry");
        }
        Future<ExplorerApi.Answer> pending;
        try {
            pending = workers.submit(() -> {
                try {
                    return api.answer(method, segments, parameters, () -> body(exchange),
                            failure -> log.accept("request failed: " + failure.getClass().getSimpleName()));
                } finally {
                    apiCalls.release();
                }
            });
        } catch (RejectedExecutionException stopping) {
            apiCalls.release();
            return ExplorerApi.Answer.error(503, "BUSY", "too many requests at once; retry");
        }
        try {
            return pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            pending.cancel(true);
            return ExplorerApi.Answer.error(503, "TIMEOUT", "the request took too long and was abandoned; retry");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return ExplorerApi.Answer.error(503, "BUSY", "the surface is stopping");
        } catch (ExecutionException failed) {
            log.accept("request failed: " + failed.getCause().getClass().getSimpleName());
            return ExplorerApi.Answer.error(500, "INTERNAL_ERROR", ExplorerApi.INTERNAL_ERROR);
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("the request body exceeds " + MAX_BODY_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** The decoded segments of a path; an empty segment, a dot segment or a broken escape is refused. */
    static List<String> segments(String rawPath) {
        List<String> segments = new ArrayList<>();
        for (String raw : rawPath.split("/", -1)) {
            String decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (decoded.isEmpty() || decoded.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("empty path segment");
            }
            segments.add(decoded);
        }
        return segments;
    }

    /** The decoded parameters of a query; a parameter given twice or without name is refused. */
    static Map<String, String> parameters(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return parameters;
        for (String pair : rawQuery.split("&", -1)) {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            if (name.isEmpty() || parameters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("invalid query parameter");
            }
        }
        return parameters;
    }

    private static void send(HttpExchange exchange, int status, String mediaType, byte[] content,
                             String contentSecurityPolicy, String allow) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", contentSecurityPolicy);
        headers.set("Cache-Control", "no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        if (mediaType != null) headers.set("Content-Type", mediaType);
        if (allow != null) headers.set("Allow", allow);
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, content.length == 0 || head ? -1 : content.length);
        if (content.length > 0 && !head) exchange.getResponseBody().write(content);
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger number = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
