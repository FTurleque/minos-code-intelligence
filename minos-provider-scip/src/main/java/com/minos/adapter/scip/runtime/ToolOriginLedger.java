package com.minos.adapter.scip.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minos.io.BoundedInputStream;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Records, under {@code MINOS_HOME/tools}, whether each managed component was seeded from the
 * distribution payload or downloaded, so that {@code doctor} can say so. It is a diagnostic: it never
 * grants trust, which is decided by the hash comparisons that precede every record.
 */
final class ToolOriginLedger {

    static final String FILE_NAME = ".tool-origins.json";
    private static final long MAX_BYTES = 256L * 1024L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    ToolOriginLedger(Path toolsRoot) {
        this.file = toolsRoot.resolve(FILE_NAME);
    }

    synchronized void record(String componentId, PinnedArtifactSource.Origin origin) throws IOException {
        ObjectNode root = read();
        root.put(componentId, origin.name().toLowerCase(java.util.Locale.ROOT));
        PrivateLocalStorage.writePrivateFile(file, JSON.writeValueAsBytes(root));
    }

    synchronized Optional<String> origin(String componentId) {
        JsonNode value = read().get(componentId);
        return value == null || !value.isTextual() ? Optional.empty() : Optional.of(value.asText());
    }

    private ObjectNode read() {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return JSON.createObjectNode();
            try (BoundedInputStream input = new BoundedInputStream(
                    ConfinedFileOpener.openRegularFileNoFollow(file), MAX_BYTES, "tool origin ledger")) {
                JsonNode tree = JSON.readTree(input.readAllBytes());
                return tree instanceof ObjectNode object ? object : JSON.createObjectNode();
            }
        } catch (IOException unreadable) {
            return JSON.createObjectNode();
        }
    }
}
