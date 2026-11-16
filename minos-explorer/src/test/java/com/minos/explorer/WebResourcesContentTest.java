package com.minos.explorer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embedded page as it is served: indexed content can only be inserted as text, nothing is loaded from
 * another origin, and nothing in it needs a relaxed content security policy.
 */
class WebResourcesContentTest {

    /** Ways to turn a string into markup or code; the page uses none of them. */
    private static final List<String> MARKUP_OR_CODE_SINKS = List.of("innerHTML", "outerHTML", "insertAdjacentHTML",
            "document.write", "eval(", "new Function", "setTimeout(\"", "setInterval(\"", "srcdoc", "javascript:",
            "createContextualFragment", "DOMParser");

    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile("(?i)(https?:|wss?:|ftp:|data:)?//[a-z0-9\\[]");
    private static final Pattern INLINE_HANDLER = Pattern.compile("(?i)\\son[a-z]+\\s*=");
    private static final Pattern INLINE_STYLE = Pattern.compile("(?i)\\sstyle\\s*=|<style");
    private static final Pattern SCRIPT_TAG = Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script>");
    private static final Pattern RESOURCE_ATTRIBUTE = Pattern.compile("(?i)\\s(?:src|href)\\s*=\\s*\"([^\"]*)\"");

    @Test
    void indexedContentCanOnlyBeInsertedAsText() throws IOException {
        Map<String, String> resources = resources();

        for (Map.Entry<String, String> resource : resources.entrySet()) {
            for (String sink : MARKUP_OR_CODE_SINKS) {
                assertFalse(resource.getValue().contains(sink), sink + " in " + resource.getKey());
            }
        }
        assertTrue(resources.get("/app.js").contains("node.textContent = String(text)"),
                "the single element factory writes text");
        for (String script : List.of("/app.js", "/map.js")) {
            assertTrue(resources.get(script).contains("\"use strict\""), script);
        }
    }

    @Test
    void nothingIsLoadedFromAnotherOriginAndNothingNeedsARelaxedPolicy() throws IOException {
        Map<String, String> resources = resources();
        String html = resources.get("/index.html");

        for (Map.Entry<String, String> resource : resources.entrySet()) {
            Matcher external = EXTERNAL_REFERENCE.matcher(withoutComments(resource.getKey(), resource.getValue()));
            assertFalse(external.find(), "external reference in " + resource.getKey());
        }
        assertFalse(INLINE_HANDLER.matcher(html).find(), "inline event handler in the page");
        assertFalse(INLINE_STYLE.matcher(html).find(), "inline style in the page");
        List<String> scripts = new ArrayList<>();
        Matcher script = SCRIPT_TAG.matcher(html);
        while (script.find()) {
            scripts.add(script.group(1).trim());
            assertEquals("", script.group(2).trim(), "inline script in the page");
        }
        assertEquals(List.of("src=\"map.js\"", "src=\"app.js\""), scripts, "the map script is loaded before the page script");
        List<String> referenced = new ArrayList<>();
        Matcher attribute = RESOURCE_ATTRIBUTE.matcher(html);
        while (attribute.find()) referenced.add(attribute.group(1));
        assertEquals(List.of("app.css", "map.js", "app.js"), referenced);
        assertFalse(resources.get("/app.css").contains("url("), "the stylesheet loads nothing");
        assertFalse(resources.get("/app.css").contains("@import"), "the stylesheet imports nothing");
        assertFalse(html.toLowerCase(java.util.Locale.ROOT).contains("<iframe"));
        assertFalse(html.toLowerCase(java.util.Locale.ROOT).contains("<form"));
    }

    @Test
    void thePageKeepsTheTokenOutOfTheAddressAndSendsItInTheAuthorizationHeaderOnly() throws IOException {
        Map<String, String> resources = resources();
        String script = resources.get("/app.js");
        String pageScripts = script + resources.get("/map.js");

        assertTrue(script.contains("window.history.replaceState(null, \"\", window.location.pathname)"),
                "the fragment that carries the token is removed from the address");
        assertTrue(script.contains("headers: { Authorization: \"Bearer \" + token }"));
        assertTrue(script.contains("credentials: \"omit\""));
        assertTrue(script.contains("referrerPolicy: \"no-referrer\""));
        assertFalse(pageScripts.contains("localStorage"), "the token does not outlive the browser tab");
        assertFalse(pageScripts.contains("document.cookie"));
        assertFalse(pageScripts.contains("?token="));
        assertEquals(1, pageScripts.split("fetch\\(", -1).length - 1, "one place sends requests");
        assertTrue(script.contains("fetch(API + path, options)"), "requests go to the API of the page's origin");
    }

    @Test
    void theMapOnlyReadsAProjectionAndNamesTheCommandThatPreparesOne() throws IOException {
        Map<String, String> resources = resources();
        String page = resources.get("/app.js");
        String script = resources.get("/map.js");
        String html = resources.get("/index.html");

        assertTrue(script.contains("api(projectPath() + \"/projection\")"), "the state is read by a GET");
        assertTrue(script.contains("api(projectPath() + \"/projection/points?offset=\""), "the points are read by a GET");
        for (String line : script.lines().filter(line -> line.contains("api(") && line.contains("projection")).toList()) {
            assertFalse(line.contains(", request") || line.contains(", {"), "a projection request carries no body: " + line);
        }
        assertEquals(1, page.split("method: body === undefined \\? \"GET\" : \"POST\"", -1).length - 1,
                "a request is a POST only when it carries a body");
        assertEquals(1, page.split("\"/search\", request\\)", -1).length - 1, "the search is the only request with a body");
        assertFalse(script.contains("\"POST\""), "the map script sends nothing");
        for (String state : List.of("ABSENTE", "ECHOUEE", "PERIMEE")) {
            assertTrue(script.contains(state + ": \""), "the page says what to do in the state " + state);
        }
        assertEquals(3, script.split("« minos semantic projection prepare <projet> »", -1).length - 1,
                "each state without a current projection names the command that prepares one");
        assertTrue(script.contains("échantillon de "), "a sample is announced");
        assertTrue(script.contains("(graine "), "with its seed");
        assertTrue(script.contains("variance expliquée"), "the method says what its axes carry");
        assertTrue(script.contains("CARTE PÉRIMÉE"), "a stale map is marked as stale");
        assertTrue(script.contains("hors carte"), "a hit that is not on the map says so");
        assertTrue(html.contains("<canvas id=\"mapCanvas\""), "the map is drawn on a canvas of the page");
        assertTrue(html.contains("La position est une projection. Le classement est calculé sur les vecteurs d'origine."));
        assertTrue(html.contains("id=\"mapShowStale\" type=\"button\" hidden"), "a stale map is not shown by default");
        assertFalse(script.contains("getContext(\"webgl"), "the map is a 2D canvas");
    }

    private static String withoutComments(String name, String content) {
        // The only "//" allowed are line comments of the script: they are removed before the search.
        return name.endsWith(".js") ? content.replaceAll("(?m)^\\s*//.*$", "") : content;
    }

    private static Map<String, String> resources() throws IOException {
        Map<String, String> resources = new LinkedHashMap<>();
        for (String path : List.of("/index.html", "/map.js", "/app.js", "/app.css")) {
            resources.put(path, new String(WebResources.find(path).orElseThrow().content(), StandardCharsets.UTF_8));
        }
        return resources;
    }
}
