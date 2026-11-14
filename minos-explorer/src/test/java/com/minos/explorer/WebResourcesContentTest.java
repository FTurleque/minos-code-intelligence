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
        assertTrue(resources.get("/app.js").contains("\"use strict\""));
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
        assertEquals(List.of("src=\"app.js\""), scripts);
        List<String> referenced = new ArrayList<>();
        Matcher attribute = RESOURCE_ATTRIBUTE.matcher(html);
        while (attribute.find()) referenced.add(attribute.group(1));
        assertEquals(List.of("app.css", "app.js"), referenced);
        assertFalse(resources.get("/app.css").contains("url("), "the stylesheet loads nothing");
        assertFalse(resources.get("/app.css").contains("@import"), "the stylesheet imports nothing");
        assertFalse(html.toLowerCase(java.util.Locale.ROOT).contains("<iframe"));
        assertFalse(html.toLowerCase(java.util.Locale.ROOT).contains("<form"));
    }

    @Test
    void thePageKeepsTheTokenOutOfTheAddressAndSendsItInTheAuthorizationHeaderOnly() throws IOException {
        String script = resources().get("/app.js");

        assertTrue(script.contains("window.history.replaceState(null, \"\", window.location.pathname)"),
                "the fragment that carries the token is removed from the address");
        assertTrue(script.contains("headers: { Authorization: \"Bearer \" + token }"));
        assertTrue(script.contains("credentials: \"omit\""));
        assertTrue(script.contains("referrerPolicy: \"no-referrer\""));
        assertFalse(script.contains("localStorage"), "the token does not outlive the browser tab");
        assertFalse(script.contains("document.cookie"));
        assertFalse(script.contains("?token="));
        assertEquals(1, script.split("fetch\\(", -1).length - 1, "one place sends requests");
        assertTrue(script.contains("fetch(API + path, options)"), "requests go to the API of the page's origin");
    }

    private static String withoutComments(String name, String content) {
        // The only "//" allowed are line comments of the script: they are removed before the search.
        return name.endsWith(".js") ? content.replaceAll("(?m)^\\s*//.*$", "") : content;
    }

    private static Map<String, String> resources() throws IOException {
        Map<String, String> resources = new LinkedHashMap<>();
        for (String path : List.of("/index.html", "/app.js", "/app.css")) {
            resources.put(path, new String(WebResources.find(path).orElseThrow().content(), StandardCharsets.UTF_8));
        }
        return resources;
    }
}
