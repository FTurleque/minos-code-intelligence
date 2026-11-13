package com.minos.explorer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * The web page of the surface, served from the jar. The set of resources is closed: a request names one of
 * them or gets nothing, so no path of the request ever reaches the class loader or the file system.
 */
final class WebResources {

    /** One embedded resource and the media type it is served with. */
    record Resource(String mediaType, byte[] content) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource resource && mediaType.equals(resource.mediaType)
                    && Arrays.equals(content, resource.content);
        }

        @Override
        public int hashCode() {
            return 31 * mediaType.hashCode() + Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "Resource[mediaType=" + mediaType + ", bytes=" + content.length + "]";
        }
    }

    private static final String BASE = "/com/minos/explorer/web/";
    private static final Map<String, String[]> BY_PATH = Map.of(
            "/", new String[] {"index.html", "text/html; charset=utf-8"},
            "/index.html", new String[] {"index.html", "text/html; charset=utf-8"},
            "/app.js", new String[] {"app.js", "text/javascript; charset=utf-8"},
            "/app.css", new String[] {"app.css", "text/css; charset=utf-8"});

    private WebResources() {
    }

    /** The paths the page is made of. */
    static java.util.Set<String> paths() {
        return BY_PATH.keySet();
    }

    /** The resource served at {@code path}, or empty when the page has none there. */
    static Optional<Resource> find(String path) throws IOException {
        String[] entry = BY_PATH.get(path);
        if (entry == null) return Optional.empty();
        try (InputStream input = WebResources.class.getResourceAsStream(BASE + entry[0])) {
            if (input == null) throw new IOException("embedded web resource is missing: " + entry[0]);
            return Optional.of(new Resource(entry[1], input.readAllBytes()));
        }
    }
}
