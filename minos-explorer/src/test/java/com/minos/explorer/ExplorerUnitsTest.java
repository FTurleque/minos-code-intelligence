package com.minos.explorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small parts of the surface, each on its own: token, guard, listening policy and body reader. */
class ExplorerUnitsTest {

    @Test
    void aTokenIs256RandomBitsMatchedOnlyByItsOwnBearerHeader() {
        SessionToken token = SessionToken.generate();
        SessionToken other = SessionToken.generate();

        assertEquals(64, token.value().length());
        assertTrue(token.value().matches("[0-9a-f]{64}"));
        assertFalse(token.value().equals(other.value()));
        assertTrue(token.matches("Bearer " + token.value()));
        assertFalse(token.matches("Bearer " + other.value()));
        assertFalse(token.matches("Bearer " + token.value() + "0"));
        assertFalse(token.matches("Bearer " + token.value().substring(1)));
        assertFalse(token.matches("bearer " + token.value()));
        assertFalse(token.matches(token.value()));
        assertFalse(token.matches("Bearer "));
        assertFalse(token.matches(null));
        assertEquals("SessionToken[redacted]", token.toString());
    }

    @Test
    void theGuardChecksTheHostThenTheOriginThenTheToken() {
        SessionToken token = SessionToken.generate();
        RequestGuard guard = RequestGuard.forPort(8765, token);
        String bearer = "Bearer " + token.value();

        assertEquals(Optional.empty(), guard.check(true, "127.0.0.1:8765", null, null, bearer));
        assertEquals(Optional.empty(), guard.check(true, "localhost:8765", "http://localhost:8765", "same-origin", bearer));
        assertEquals(Optional.empty(), guard.check(true, "[::1]:8765", "http://[::1]:8765", "none", bearer));
        assertEquals(Optional.empty(), guard.check(false, "127.0.0.1:8765", null, null, null));
        assertEquals(Optional.of(RequestGuard.Refusal.HOST), guard.check(true, null, null, null, bearer));
        assertEquals(Optional.of(RequestGuard.Refusal.HOST), guard.check(false, "example.org", null, null, null));
        assertEquals(Optional.of(RequestGuard.Refusal.HOST),
                guard.check(true, "example.org:8765", "https://autre.example", null, null));
        assertEquals(Optional.of(RequestGuard.Refusal.ORIGIN),
                guard.check(true, "127.0.0.1:8765", "https://autre.example", null, null));
        assertEquals(Optional.of(RequestGuard.Refusal.ORIGIN),
                guard.check(false, "127.0.0.1:8765", "http://127.0.0.1:9000", null, null));
        assertEquals(Optional.of(RequestGuard.Refusal.ORIGIN),
                guard.check(true, "127.0.0.1:8765", null, "same-site", bearer));
        assertEquals(Optional.of(RequestGuard.Refusal.TOKEN), guard.check(true, "127.0.0.1:8765", null, null, null));
        assertEquals(403, RequestGuard.Refusal.HOST.status());
        assertEquals(403, RequestGuard.Refusal.ORIGIN.status());
        assertEquals(401, RequestGuard.Refusal.TOKEN.status());
    }

    @Test
    void theSurfaceListensOnTheLoopbackUnlessTheDockerRuntimeAsksInsideAContainer() {
        Map<String, String> docker = Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "enabled",
                ListeningPolicy.RUNTIME_LOCATION_ENV, "docker");

        assertEquals(ListeningPolicy.Scope.LOOPBACK, ListeningPolicy.resolve(Map.of(), () -> true));
        assertEquals(ListeningPolicy.Scope.LOOPBACK,
                ListeningPolicy.resolve(Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, " "), () -> true));
        assertEquals(ListeningPolicy.Scope.LOOPBACK,
                ListeningPolicy.resolve(Map.of(ListeningPolicy.RUNTIME_LOCATION_ENV, "docker"), () -> true));
        assertEquals(ListeningPolicy.Scope.CONTAINER_INTERFACE, ListeningPolicy.resolve(docker, () -> true));
        assertEquals(ListeningPolicy.Scope.CONTAINER_INTERFACE, ListeningPolicy.resolve(
                Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, " Enabled ", ListeningPolicy.RUNTIME_LOCATION_ENV, "Docker"),
                () -> true));
        for (InetAddress address : ListeningPolicy.addresses(ListeningPolicy.Scope.LOOPBACK)) {
            assertTrue(address.isLoopbackAddress(), address.toString());
        }
        assertEquals(2, ListeningPolicy.addresses(ListeningPolicy.Scope.LOOPBACK).size());
        assertTrue(ListeningPolicy.addresses(ListeningPolicy.Scope.CONTAINER_INTERFACE).getFirst().isAnyLocalAddress());
    }

    @Test
    void containerListeningIsRefusedOutsideAContainerNeverDowngradedInSilence() {
        Map<String, String> docker = Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "enabled",
                ListeningPolicy.RUNTIME_LOCATION_ENV, "docker");

        IllegalArgumentException notInContainer = assertThrows(IllegalArgumentException.class,
                () -> ListeningPolicy.resolve(docker, () -> false));
        IllegalArgumentException notDocker = assertThrows(IllegalArgumentException.class,
                () -> ListeningPolicy.resolve(Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "enabled"), () -> true));
        IllegalArgumentException native_ = assertThrows(IllegalArgumentException.class,
                () -> ListeningPolicy.resolve(Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "enabled",
                        ListeningPolicy.RUNTIME_LOCATION_ENV, "native"), () -> true));
        IllegalArgumentException address = assertThrows(IllegalArgumentException.class,
                () -> ListeningPolicy.resolve(Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "0.0.0.0",
                        ListeningPolicy.RUNTIME_LOCATION_ENV, "docker"), () -> true));

        assertEquals(ListeningPolicy.REFUSED_OUTSIDE_CONTAINER, notInContainer.getMessage());
        assertEquals(ListeningPolicy.REFUSED_OUTSIDE_CONTAINER, notDocker.getMessage());
        assertEquals(ListeningPolicy.REFUSED_OUTSIDE_CONTAINER, native_.getMessage());
        assertEquals(ListeningPolicy.INVALID_VALUE, address.getMessage());
    }

    @Test
    void aContainerIsALinuxProcessWhoseRootHoldsAnEngineMarker(@TempDir Path temp) throws Exception {
        Path marker = Files.writeString(temp.resolve("dockerenv"), "");
        Path absent = temp.resolve("absent");
        Path directory = Files.createDirectory(temp.resolve("directory"));

        assertTrue(ListeningPolicy.runsInContainer(List.of(absent, marker), "Linux"));
        assertFalse(ListeningPolicy.runsInContainer(List.of(absent, directory), "Linux"));
        assertFalse(ListeningPolicy.runsInContainer(List.of(marker), "Windows 10"));
        assertFalse(ListeningPolicy.runsInContainer(List.of(marker), "Mac OS X"));
        assertFalse(ListeningPolicy.runsInContainer(List.of(), "Linux"));
    }

    @Test
    void theBodyReaderAcceptsOneFlatObjectAndDecodesItsStrings() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("query", "a \"b\" \\ / \n\t\u00e9 \u20ac");
        expected.put("limit", 20.0);
        expected.put("minimumScore", -0.5);
        expected.put("exponent", 1500.0);
        expected.put("flag", true);
        expected.put("off", false);
        expected.put("kind", null);

        Map<String, Object> read = JsonObjectReader.read(" {\"query\":\"a \\\"b\\\" \\\\ \\/ \\n\\t\\u00e9 \u20ac\","
                + " \"limit\": 20, \"minimumScore\":-0.5,\"exponent\":1.5e3,\"flag\":true,\"off\":false,\"kind\":null}\n");

        assertEquals(expected, read);
        assertEquals(Map.of(), JsonObjectReader.read("{}"));
        assertNull(JsonObjectReader.read("{\"a\":null}").get("a"));
    }

    @Test
    void theBodyReaderRefusesEverythingElseWithoutRepeatingTheInput() {
        for (String invalid : new String[] {null, "", "[]", "\"text\"", "{", "{\"a\"}", "{\"a\":}", "{\"a\":1,}",
                "{\"a\":1}{}", "{\"a\":1} x", "{\"a\":{\"b\":1}}", "{\"a\":[1]}", "{\"a\":1,\"a\":2}", "{a:1}",
                "{\"a\":01}", "{\"a\":1.}", "{\"a\":+1}", "{\"a\":1e999}", "{\"a\":NaN}", "{\"a\":tru}",
                "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\uzzzz\"}", "{\"a\":\"line\nbreak\"}",
                "{\"a\":\"unterminated}", "{\"secret-marker\":nul}"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> JsonObjectReader.read(invalid), String.valueOf(invalid));
            assertEquals("the request body must be one flat JSON object", refused.getMessage());
        }
        StringBuilder many = new StringBuilder("{");
        for (int index = 0; index < 17; index++) many.append(index == 0 ? "" : ",").append("\"k").append(index).append("\":1");
        assertThrows(IllegalArgumentException.class, () -> JsonObjectReader.read(many.append('}').toString()));
    }

    @Test
    void theEmbeddedPageIsAClosedSetOfResources() throws Exception {
        assertEquals(java.util.Set.of("/", "/index.html", "/app.js", "/app.css"), WebResources.paths());
        assertEquals("text/html; charset=utf-8", WebResources.find("/").orElseThrow().mediaType());
        assertEquals(Optional.empty(), WebResources.find("/pom.xml"));
        WebResources.Resource page = WebResources.find("/").orElseThrow();
        assertEquals(page, WebResources.find("/index.html").orElseThrow(), "two readings of one resource are equal");
        assertEquals(page.hashCode(), WebResources.find("/index.html").orElseThrow().hashCode());
        assertFalse(page.equals(WebResources.find("/app.js").orElseThrow()));
        assertEquals("Resource[mediaType=text/html; charset=utf-8, bytes=" + page.content().length + "]", page.toString());
        assertEquals(Optional.empty(), WebResources.find("/../web/app.js"));
        assertEquals("1", ExplorerContract.VERSION);
        assertEquals("/api/v1/", ExplorerContract.API_PREFIX);
    }
}
