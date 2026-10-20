package com.minos.application;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI import history (AUD-SEC-03): written in one private piece, byte for byte as {@link Properties#store}
 * renders it, nothing left beside it.
 */
class LocalProjectOperationsHistoryTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-10-18T10:15:30Z");

    /** The rendering of today, without the dated comment line which {@code Properties.store} adds. */
    private static List<String> expectedLines(String snapshotId, String providerVersion) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("snapshotId", snapshotId);
        properties.setProperty("providerId", "scip-java");
        properties.setProperty("providerVersion", providerVersion);
        properties.setProperty("completedAt", COMPLETED_AT.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "MINOS project index history");
        return withoutDate(bytes.toString(StandardCharsets.ISO_8859_1));
    }

    private static List<String> withoutDate(String content) {
        return content.lines().filter(line -> !line.startsWith("#") || line.contains("MINOS")).sorted().toList();
    }

    private static List<String> written(Path file) throws Exception {
        return withoutDate(Files.readString(file, StandardCharsets.ISO_8859_1));
    }

    @Test
    void anAsciiHistoryIsWrittenAsPropertiesAndPrivate(@TempDir Path directory) throws Exception {
        UUID project = UUID.randomUUID();
        Path history = directory.resolve("history");

        LocalProjectOperations.writeHistory(history, project, new LocalProjectOperations.IndexHistory(
                "snapshot-1", "scip-java", "1.2.3", COMPLETED_AT));

        Path file = history.resolve(project + ".properties");
        assertEquals(expectedLines("snapshot-1", "1.2.3"), written(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    void aNonAsciiProviderVersionKeepsTheLatin1EscapedEncoding(@TempDir Path directory) throws Exception {
        UUID project = UUID.randomUUID();
        Path history = directory.resolve("history");

        LocalProjectOperations.writeHistory(history, project, new LocalProjectOperations.IndexHistory(
                "snapshot-2", "scip-java", "é-€-1", COMPLETED_AT));

        Path file = history.resolve(project + ".properties");
        assertEquals(expectedLines("snapshot-2", "é-€-1"), written(file));
        assertTrue(Files.readString(file, StandardCharsets.ISO_8859_1).contains("\\u00E9"),
                "a non-ASCII character is stored as a \\uXXXX escape, as before");
    }

    @Test
    void aMissingProviderVersionIsStoredEmpty(@TempDir Path directory) throws Exception {
        UUID project = UUID.randomUUID();
        Path history = directory.resolve("history");

        LocalProjectOperations.writeHistory(history, project, new LocalProjectOperations.IndexHistory(
                "snapshot-3", "scip-java", null, COMPLETED_AT));

        assertEquals(expectedLines("snapshot-3", ""), written(history.resolve(project + ".properties")));
    }

    @Test
    void aSecondWriteReplacesTheFirstAndLeavesNoTemporary(@TempDir Path directory) throws Exception {
        UUID project = UUID.randomUUID();
        Path history = directory.resolve("history");
        LocalProjectOperations.writeHistory(history, project, new LocalProjectOperations.IndexHistory(
                "snapshot-old", "scip-java", "1", COMPLETED_AT));

        LocalProjectOperations.writeHistory(history, project, new LocalProjectOperations.IndexHistory(
                "snapshot-new", "scip-java", "2", COMPLETED_AT));

        assertEquals(expectedLines("snapshot-new", "2"), written(history.resolve(project + ".properties")));
        try (Stream<Path> siblings = Files.list(history)) {
            List<String> names = siblings.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of(project + ".properties"), names);
        }
    }
}
