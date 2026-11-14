package com.minos.explorer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the listener lets through and what it refuses, observed on real sockets: loopback binding, the token,
 * the {@code Host} and {@code Origin} checks, the security headers and the bounds of a request.
 */
class ExplorerServerTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private volatile ExplorerServer.Calls behaviour = (method, segments, parameters, body, failures) ->
            ExplorerApi.Answer.ok(Map.of("segments", segments, "parameters", parameters));
    private ExplorerServer server;
    private int port;
    private String token;

    @BeforeEach
    void start() throws IOException {
        server = ExplorerServer.start((method, segments, parameters, body, failures) -> {
            calls.incrementAndGet();
            methods.add(method);
            return behaviour.answer(method, segments, parameters, body, failures);
        }, 0, ListeningPolicy.Scope.LOOPBACK, Duration.ofSeconds(2), log::add);
        port = server.port();
        token = server.accessUrl().substring(server.accessUrl().indexOf("#token=") + "#token=".length());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theSurfaceIsBoundToLoopbackAddressesOnlyAndItsUrlNamesTheIpv4Loopback() throws Exception {
        assertFalse(server.boundAddresses().isEmpty());
        for (InetSocketAddress bound : server.boundAddresses()) {
            assertTrue(bound.getAddress().isLoopbackAddress(), "bound to " + bound);
            assertEquals(port, bound.getPort());
        }
        assertTrue(server.accessUrl().startsWith("http://127.0.0.1:" + port + "/#token="), "access URL shape");
        assertEquals(64, token.length());
        for (InetAddress address : nonLoopbackAddresses()) {
            assertThrows(IOException.class, () -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, port), 2_000);
                }
            }, "a connection through " + address + " must be refused");
        }
    }

    @Test
    void twoSessionsNeverShareATokenAndTheTokenIsNeverLogged() throws Exception {
        try (ExplorerServer other = ExplorerServer.start(behaviour, 0, ListeningPolicy.Scope.LOOPBACK,
                Duration.ofSeconds(2), log::add)) {
            assertFalse(other.accessUrl().endsWith(token));
        }
        RawHttp.get(port, "/api/v1/projects", Map.of());
        RawHttp.get(port, "/api/v1/projects", bearer());
        assertTrue(log.stream().noneMatch(line -> line.contains(token)), log.toString());
    }

    @Test
    void anApiRequestWithoutTheTokenIsRefusedWithoutDetailAndReachesNoService() throws Exception {
        RawHttp.Response missing = RawHttp.get(port, "/api/v1/projects", Map.of());
        RawHttp.Response wrong = RawHttp.get(port, "/api/v1/projects", Map.of("Authorization", "Bearer " + "0".repeat(64)));
        RawHttp.Response notBearer = RawHttp.get(port, "/api/v1/projects", Map.of("Authorization", token));
        RawHttp.Response inQuery = RawHttp.get(port, "/api/v1/projects?token=" + token, Map.of());

        for (RawHttp.Response refused : List.of(missing, wrong, notBearer, inQuery)) {
            assertEquals(401, refused.status());
            assertEquals("", refused.body());
            assertNull(refused.header("Set-Cookie"));
            assertNull(refused.header("Access-Control-Allow-Origin"));
        }
        assertEquals(0, calls.get(), "no service call on a refused request");
        assertTrue(log.contains("refused request: token"), log.toString());
    }

    @Test
    void aRequestWhoseHostIsNotALoopbackNameOfThisPortIsRefusedEvenWithTheToken() throws Exception {
        for (String host : List.of("attaquant.example:8765", "attaquant.example:" + port, "127.0.0.1:" + (port + 1),
                "127.0.0.1", "localhost.attaquant.example:" + port)) {
            RawHttp.Response refused = RawHttp.get(port, "/api/v1/projects", headers(host, null));

            assertEquals(403, refused.status(), host);
            assertEquals("", refused.body());
        }
        RawHttp.Response page = RawHttp.get(port, "/", Map.of("Host", "attaquant.example:" + port));
        assertEquals(403, page.status(), "the page itself is not served under another name");
        for (String host : List.of("127.0.0.1:" + port, "localhost:" + port, "LOCALHOST:" + port, "[::1]:" + port)) {
            assertEquals(200, RawHttp.get(port, "/api/v1/projects", headers(host, null)).status(), host);
        }
        assertEquals(4, calls.get());
    }

    @Test
    void thePageAndItsTwoResourcesAreServedToANavigationComingFromALinkOfAnotherSite() throws Exception {
        for (String site : List.of("cross-site", "same-site", "CROSS-SITE")) {
            RawHttp.Response page = RawHttp.get(port, "/", Map.of("Sec-Fetch-Site", site));

            assertEquals(200, page.status(), site);
            assertTrue(page.body().startsWith("<!DOCTYPE html>"), site);
            assertFalse(page.body().contains(token), "the page never holds the token");
            for (String resource : List.of("/app.css", "/app.js")) {
                assertEquals(200, RawHttp.get(port, resource, Map.of("Sec-Fetch-Site", site)).status(), resource);
            }
        }
        RawHttp.Response linked = RawHttp.get(port, "/?x=1", Map.of("Sec-Fetch-Site", "cross-site"));
        assertEquals(200, linked.status(), "the query string is not part of the path");
        assertNull(linked.header("Access-Control-Allow-Origin"));
        assertEquals("same-origin", linked.header("Cross-Origin-Resource-Policy"));
        assertEquals("same-origin", linked.header("Cross-Origin-Opener-Policy"));
        assertEquals("DENY", linked.header("X-Frame-Options"));
        assertEquals("nosniff", linked.header("X-Content-Type-Options"));
        assertEquals("no-referrer", linked.header("Referrer-Policy"));
        assertEquals(ExplorerServer.PAGE_CONTENT_SECURITY_POLICY, linked.header("Content-Security-Policy"));
        assertEquals(0, calls.get());
    }

    @Test
    void nothingButTheReadOfThePageIsOpenedToARequestComingFromAnotherSite() throws Exception {
        Map<String, String> crossSite = Map.of("Sec-Fetch-Site", "cross-site");
        Map<String, String> crossSiteWithToken = new java.util.LinkedHashMap<>(bearer());
        crossSiteWithToken.put("Sec-Fetch-Site", "cross-site");
        Map<String, String> sameSiteWithToken = new java.util.LinkedHashMap<>(bearer());
        sameSiteWithToken.put("Sec-Fetch-Site", "same-site");

        assertEquals(403, RawHttp.get(port, "/api/v1/projects", crossSiteWithToken).status());
        assertEquals(403, RawHttp.get(port, "/api/v1/projects", sameSiteWithToken).status());
        assertEquals(403, RawHttp.get(port, "/api/v1/projects", crossSite).status());
        assertEquals(403, RawHttp.send(port, "POST", "/", crossSite, "").status(), "a navigation is a GET");
        assertEquals(403, RawHttp.send(port, "HEAD", "/", crossSite, null).status());
        assertEquals(403, RawHttp.get(port, "/absent.js", crossSite).status(), "only the three resources");
        assertEquals(403, RawHttp.get(port, "/index.html", crossSite).status(), "only the three resources");
        assertEquals(200, RawHttp.get(port, "/index.html", Map.of()).status());
        assertEquals(403, RawHttp.send(port, "get", "/", crossSite, null).status(), "the method is GET, exactly");
        for (String almostApi : List.of("/api", "/API/v1/projects", "/app.js/", "/./", "/app.js;x")) {
            assertEquals(403, RawHttp.get(port, almostApi, crossSiteWithToken).status(), almostApi);
        }
        RawHttp.Response refusedApi = RawHttp.get(port, "/api/v1/projects", crossSiteWithToken);
        assertEquals("", refusedApi.body(), "an API refusal has no body");
        assertNull(refusedApi.header("Content-Type"));
        assertEquals("", RawHttp.send(port, "HEAD", "/", crossSite, null).body());
        Map<String, String> foreignHost = new java.util.LinkedHashMap<>(crossSite);
        foreignHost.put("Host", "attaquant.example:" + port);
        assertEquals(403, RawHttp.get(port, "/", foreignHost).status(), "Host is checked on the page too");
        Map<String, String> foreignOrigin = new java.util.LinkedHashMap<>(crossSite);
        foreignOrigin.put("Origin", "https://autre.example");
        assertEquals(403, RawHttp.get(port, "/", foreignOrigin).status(), "Origin is checked on the page too");
        assertEquals(0, calls.get(), "no service call on a refused request");
    }

    @Test
    void aRefusedPageRequestSaysHowToOpenTheSurfaceAndNothingAboutTheRefusal() throws Exception {
        RawHttp.Response refused = RawHttp.get(port, "/", Map.of("Host", "attaquant.example:" + port));
        RawHttp.Response refusedPost = RawHttp.send(port, "POST", "/", Map.of("Sec-Fetch-Site", "cross-site"), "");

        for (RawHttp.Response answer : List.of(refused, refusedPost)) {
            assertEquals(403, answer.status());
            assertEquals("text/plain; charset=utf-8", answer.header("Content-Type"));
            assertTrue(answer.body().contains("minos explorer serve"), answer.body());
            for (String leak : List.of("host", "origin", "sec-fetch", "attaquant", token, ":\\", "Exception")) {
                assertFalse(answer.body().toLowerCase(java.util.Locale.ROOT)
                        .contains(leak.toLowerCase(java.util.Locale.ROOT)), leak);
            }
            assertNull(answer.header("Access-Control-Allow-Origin"));
        }
        assertEquals(refused.body(), refusedPost.body(), "one text, whatever the refusal");
    }

    @Test
    void aRequestFromAnotherOriginIsRefusedAndNoCorsHeaderIsEverSent() throws Exception {
        for (String origin : List.of("https://autre.example", "http://127.0.0.1:" + (port + 1), "null",
                "http://127.0.0.1." + port + ".autre.example", "https://127.0.0.1:" + port)) {
            RawHttp.Response refused = RawHttp.get(port, "/api/v1/projects", headers("127.0.0.1:" + port, origin));

            assertEquals(403, refused.status(), origin);
            assertFalse(refused.raw().toLowerCase(java.util.Locale.ROOT).contains("access-control-"), origin);
        }
        Map<String, String> crossSite = new java.util.LinkedHashMap<>(bearer());
        crossSite.put("Sec-Fetch-Site", "cross-site");
        assertEquals(403, RawHttp.get(port, "/api/v1/projects", crossSite).status());
        assertEquals(0, calls.get());

        RawHttp.Response sameOrigin = RawHttp.get(port, "/api/v1/projects",
                headers("127.0.0.1:" + port, "http://127.0.0.1:" + port));
        RawHttp.Response preflight = RawHttp.send(port, "OPTIONS", "/api/v1/projects",
                Map.of("Origin", "https://autre.example", "Access-Control-Request-Method", "POST"), null);
        assertEquals(200, sameOrigin.status());
        assertFalse(sameOrigin.raw().toLowerCase(java.util.Locale.ROOT).contains("access-control-"));
        assertEquals(403, preflight.status());
        assertTrue(log.contains("refused request: origin"), log.toString());
    }

    @Test
    void everyAnswerCarriesTheSecurityHeadersAndNoCookie() throws Exception {
        RawHttp.Response data = RawHttp.get(port, "/api/v1/projects", bearer());
        RawHttp.Response page = RawHttp.get(port, "/", Map.of());
        RawHttp.Response script = RawHttp.get(port, "/app.js", Map.of());
        RawHttp.Response refused = RawHttp.get(port, "/api/v1/projects", Map.of());

        assertEquals("application/json; charset=utf-8", data.header("Content-Type"));
        assertEquals("text/html; charset=utf-8", page.header("Content-Type"));
        assertEquals("text/javascript; charset=utf-8", script.header("Content-Type"));
        for (RawHttp.Response answer : List.of(data, page, script, refused)) {
            assertEquals("nosniff", answer.header("X-Content-Type-Options"));
            assertEquals("no-store", answer.header("Cache-Control"));
            assertEquals("no-referrer", answer.header("Referrer-Policy"));
            assertEquals("DENY", answer.header("X-Frame-Options"));
            assertNull(answer.header("Set-Cookie"));
            String policy = answer.header("Content-Security-Policy");
            assertTrue(policy.startsWith("default-src 'none'"), policy);
            assertFalse(policy.contains("unsafe-inline"), policy);
            assertFalse(policy.contains("unsafe-eval"), policy);
            assertFalse(policy.contains("http"), policy);
            assertFalse(policy.contains("*"), policy);
        }
        assertTrue(page.header("Content-Security-Policy").contains("script-src 'self'"));
        assertTrue(page.header("Content-Security-Policy").contains("frame-ancestors 'none'"));
    }

    @Test
    void thePageIsAClosedSetOfEmbeddedResourcesReadWithGetOnly() throws Exception {
        for (String path : WebResources.paths()) {
            assertEquals(200, RawHttp.get(port, path, Map.of()).status(), path);
        }
        for (String path : List.of("/app.js.map", "/../pom.xml", "/%2e%2e/%2e%2e/pom.xml", "/com/minos/explorer/web/app.js",
                "/favicon.ico")) {
            RawHttp.Response absent = RawHttp.get(port, path, Map.of());
            assertEquals(404, absent.status(), path);
            assertEquals("", absent.body());
        }
        // An address the JDK server cannot parse never reaches the surface: it answers 400 itself.
        assertEquals(400, RawHttp.get(port, "//app.js", Map.of()).status());
        RawHttp.Response post = RawHttp.send(port, "POST", "/", Map.of(), "x");
        assertEquals(405, post.status());
        assertEquals("GET, HEAD", post.header("Allow"));
        assertEquals("", RawHttp.send(port, "HEAD", "/", Map.of(), null).body());
        assertEquals(0, calls.get());
    }

    @Test
    void pathSegmentsAndParametersAreDecodedOnceAndMalformedAddressesAreRefused() throws Exception {
        RawHttp.Response decoded = RawHttp.get(port,
                "/api/v1/projects/p%201/documents/file%3Asrc%2FA%2Bb.java?filePrefix=src%2F&module=a+b", bearer());
        RawHttp.Response outsidePrefix = RawHttp.get(port, "/api/v2/projects", bearer());
        RawHttp.Response emptySegment = RawHttp.get(port, "/api/v1/projects//documents", bearer());
        RawHttp.Response brokenEscape = RawHttp.get(port, "/api/v1/projects/%zz", bearer());
        RawHttp.Response duplicate = RawHttp.get(port, "/api/v1/projects?limit=1&limit=2", bearer());

        assertEquals(200, decoded.status());
        assertTrue(decoded.body().contains("\"file:src/A+b.java\""), decoded.body());
        assertTrue(decoded.body().contains("\"p 1\""), decoded.body());
        assertTrue(decoded.body().contains("\"filePrefix\":\"src/\""), decoded.body());
        assertTrue(decoded.body().contains("\"module\":\"a b\""), decoded.body());
        assertEquals(404, outsidePrefix.status());
        assertEquals(400, emptySegment.status());
        assertEquals(400, brokenEscape.status());
        assertEquals(400, duplicate.status());
        assertEquals(1, calls.get());
        assertEquals(List.of("a", "b c", "d+e"), ExplorerServer.segments("a/b%20c/d+e"));
        assertEquals(Map.of("a", "", "b", "c d"), ExplorerServer.parameters("a&b=c+d"));
        assertEquals(Map.of(), ExplorerServer.parameters(null));
    }

    @Test
    void aBodyIsAcceptedAsJsonOnlyAndWithinItsBound() throws Exception {
        behaviour = (method, segments, parameters, body, failures) -> {
            try {
                return ExplorerApi.Answer.ok(Map.of("length", body.read().length()));
            } catch (IOException | IllegalArgumentException refused) {
                return ExplorerApi.Answer.error(400, "INVALID_REQUEST", refused.getMessage());
            }
        };
        Map<String, String> json = new java.util.LinkedHashMap<>(bearer());
        json.put("Content-Type", "application/json");
        Map<String, String> form = new java.util.LinkedHashMap<>(bearer());
        form.put("Content-Type", "application/x-www-form-urlencoded");

        RawHttp.Response accepted = RawHttp.send(port, "POST", "/api/v1/projects/p/search", json, "{\"query\":\"x\"}");
        RawHttp.Response wrongType = RawHttp.send(port, "POST", "/api/v1/projects/p/search", form, "query=x");
        RawHttp.Response noType = RawHttp.send(port, "POST", "/api/v1/projects/p/search", bearer(), "{}");
        RawHttp.Response oversized = RawHttp.send(port, "POST", "/api/v1/projects/p/search", json,
                "x".repeat(ExplorerServer.MAX_BODY_BYTES + 1));

        assertEquals(200, accepted.status());
        assertTrue(accepted.body().contains("\"length\":13"), accepted.body());
        assertEquals(415, wrongType.status());
        assertEquals(415, noType.status());
        assertEquals(400, oversized.status());
        assertTrue(oversized.body().contains("exceeds " + ExplorerServer.MAX_BODY_BYTES + " bytes"), oversized.body());
        assertEquals(2, calls.get(), "a body of another media type reaches no service");
    }

    @Test
    void aRequestThatLastsTooLongIsAbandonedAndTheSurfaceStaysUsable() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = (method, segments, parameters, body, failures) -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ExplorerApi.Answer.ok(Map.of());
        };

        RawHttp.Response slow = RawHttp.get(port, "/api/v1/projects", bearer());
        release.countDown();
        behaviour = (method, segments, parameters, body, failures) -> ExplorerApi.Answer.ok(Map.of("ok", true));
        RawHttp.Response next = RawHttp.get(port, "/api/v1/projects", bearer());

        assertEquals(503, slow.status());
        assertTrue(slow.body().contains("\"code\":\"TIMEOUT\""), slow.body());
        assertEquals(200, next.status());
    }

    @Test
    void requestsBeyondTheConcurrencyBoundAreRefusedAsBusyNotQueuedWithoutEnd() throws Exception {
        CountDownLatch entered = new CountDownLatch(ExplorerServer.MAX_CONCURRENT_API_CALLS);
        CountDownLatch release = new CountDownLatch(1);
        behaviour = (method, segments, parameters, body, failures) -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ExplorerApi.Answer.ok(Map.of());
        };
        List<Thread> holders = new ArrayList<>();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < ExplorerServer.MAX_CONCURRENT_API_CALLS; index++) {
            Thread holder = new Thread(() -> {
                try {
                    statuses.add(RawHttp.get(port, "/api/v1/projects", bearer()).status());
                } catch (IOException failure) {
                    statuses.add(-1);
                }
            });
            holder.start();
            holders.add(holder);
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the bound number of calls is running");

        RawHttp.Response extra = RawHttp.get(port, "/api/v1/projects", bearer());
        release.countDown();
        for (Thread holder : holders) holder.join(10_000);

        assertEquals(503, extra.status());
        assertTrue(extra.body().contains("\"code\":\"BUSY\""), extra.body());
        assertEquals(Collections.nCopies(ExplorerServer.MAX_CONCURRENT_API_CALLS, 200), statuses);
        assertEquals(ExplorerServer.MAX_CONCURRENT_API_CALLS, calls.get());
    }

    @Test
    void aFailureOfTheHandlerIsAnOpaqueErrorThatNamesOnlyItsType() throws Exception {
        behaviour = (method, segments, parameters, body, failures) -> {
            throw new IllegalStateException("C:\\secret\\root is broken");
        };

        RawHttp.Response failed = RawHttp.get(port, "/api/v1/projects", bearer());

        assertEquals(500, failed.status());
        assertFalse(failed.body().contains("secret"), failed.body());
        assertTrue(failed.body().contains("\"code\":\"INTERNAL_ERROR\""), failed.body());
        assertTrue(log.contains("request failed: IllegalStateException"), log.toString());
        assertTrue(log.stream().noneMatch(line -> line.contains("secret")), log.toString());
    }

    @Test
    void connectionsThatNeverFinishTheirRequestAreCutByTheServerWhichThenAnswersAgain() throws Exception {
        List<Socket> slow = new ArrayList<>();
        try {
            for (int index = 0; index < 12; index++) {
                Socket socket = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
                socket.setSoTimeout(30_000);
                socket.getOutputStream().write('G');
                socket.getOutputStream().flush();
                slow.add(socket);
            }
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(ExplorerServer.MAX_REQUEST_READ_SECONDS + 20);

            // The slow connections are never closed by this test: the surface must get rid of them itself. A
            // request that arrives while they hold every thread may be cut with them; the next one is served.
            int status = -1;
            while (status != 200 && System.nanoTime() < deadline) {
                try {
                    status = RawHttp.get(port, "/api/v1/projects", bearer()).status();
                } catch (IOException | IllegalStateException cutWithTheSlowOnes) {
                    status = -1;
                }
            }

            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
            assertEquals(200, status, "the surface answers again " + seconds + " s after 12 stalled connections");
            assertEquals(Integer.toString(ExplorerServer.MAX_REQUEST_READ_SECONDS),
                    System.getProperty("sun.net.httpserver.maxReqTime"));
            assertTrue(closedByTheServer(slow.getFirst()), "a stalled connection is closed by the surface");
        } finally {
            for (Socket socket : slow) socket.close();
        }
    }

    private static boolean closedByTheServer(Socket socket) {
        try {
            return socket.getInputStream().read() == -1;
        } catch (java.net.SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true;
        }
    }

    @Test
    void theSurfaceDoesNotStartBesideAnotherProcessThatHoldsItsPortOnTheIpv6Loopback() throws Exception {
        byte[] loopback = new byte[16];
        loopback[15] = 1;
        InetAddress ipv6Loopback = InetAddress.getByAddress(loopback);
        org.junit.jupiter.api.Assumptions.assumeTrue(ListeningPolicy.hostHasAddress(ipv6Loopback),
                "this machine has no IPv6 loopback address");
        try (java.net.ServerSocket other = new java.net.ServerSocket(0, 1, ipv6Loopback)) {
            int taken = other.getLocalPort();

            assertThrows(IOException.class, () -> ExplorerServer.start(behaviour, taken,
                    ListeningPolicy.Scope.LOOPBACK, Duration.ofSeconds(2), log::add));

            assertThrows(ConnectException.class, () -> RawHttp.get(taken, "/", Map.of()),
                    "nothing is left listening on the IPv4 loopback of that port");
        }
        assertTrue(ListeningPolicy.hostHasAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})));
        assertFalse(ListeningPolicy.hostHasAddress(InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 77})));
    }

    @Test
    void aClosedSurfaceNoLongerListens() throws Exception {
        server.close();

        assertThrows(ConnectException.class, () -> RawHttp.get(port, "/", Map.of()));
    }

    private Map<String, String> bearer() {
        return Map.of("Authorization", "Bearer " + token);
    }

    private Map<String, String> headers(String host, String origin) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Host", host);
        headers.put("Authorization", "Bearer " + token);
        if (origin != null) headers.put("Origin", origin);
        return headers;
    }

    private static List<InetAddress> nonLoopbackAddresses() throws IOException {
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) addresses.add(address);
            }
        }
        return addresses;
    }
}
