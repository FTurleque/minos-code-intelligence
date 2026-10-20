package com.minos.app;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saving of the backend configuration (AUD-SEC-03): the file is written in one private piece, byte for byte as
 * {@link Properties#store} renders it, and nothing is left beside it.
 */
class McpBackendConfigurationStoreSaveTest {

    private static McpBackendConfiguration configuration(String container) {
        return new McpBackendConfiguration(McpBackendConfiguration.CURRENT_FORMAT_VERSION, McpBackend.DOCKER,
                container, Duration.ofMillis(2500));
    }

    /** The expected rendering, without the dated comment line which {@code Properties.store} adds. */
    private static List<String> expectedLines(String container) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "1");
        properties.setProperty("backend", McpBackend.DOCKER.configurationValue());
        properties.setProperty("docker.containerName", container);
        properties.setProperty("docker.probeTimeoutMillis", "2500");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            properties.store(writer, "MINOS MCP backend configuration v1");
        }
        return linesWithoutDate(bytes.toString(StandardCharsets.UTF_8));
    }

    private static List<String> linesWithoutDate(String content) {
        return content.lines().filter(line -> !line.startsWith("#") || line.contains("MINOS")).toList();
    }

    @Test
    void theFileHoldsTheFourKeysAsProperties(@TempDir Path home) throws Exception {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);

        store.save(configuration("minos-mcp-prod"));

        List<String> written = linesWithoutDate(Files.readString(store.file(), StandardCharsets.UTF_8));
        List<String> expected = expectedLines("minos-mcp-prod");
        assertEquals(expected.stream().sorted().toList(), written.stream().sorted().toList());
        assertEquals(configuration("minos-mcp-prod"), store.loadOrMigrate());
    }

    @Test
    void theFileIsPrivateAndAReplacementKeepsItSo(@TempDir Path home) throws Exception {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);
        store.save(configuration("first"));
        Path file = store.file();
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));

        store.save(configuration("second"));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
        assertEquals("second", store.loadOrMigrate().dockerContainerName());
    }

    @Test
    void anExistingPermissiveFileIsHardenedByTheReplacement(@TempDir Path home) throws Exception {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);
        store.save(configuration("first"));
        // A file made by an older MINOS, readable beyond its owner: saving makes it owner-only again.
        PrivateLocalStorage.hardenExistingFile(store.file());

        store.save(configuration("second"));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(store.file()));
    }

    @Test
    void noTemporaryIsLeftBesideTheFile(@TempDir Path home) throws Exception {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(home);

        store.save(configuration("only"));

        try (Stream<Path> siblings = Files.list(store.file().getParent())) {
            List<String> names = siblings.map(path -> path.getFileName().toString()).toList();
            assertTrue(names.stream().noneMatch(name -> name.endsWith(".tmp")), names.toString());
        }
    }
}
