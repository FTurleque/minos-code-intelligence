package com.minos.storage.home;

import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexImportHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import history file: the format written by earlier versions is read unchanged, a write is private, atomic
 * and leaves nothing beside the file, and an unreadable history is refused by its leaf name only.
 */
class FileIndexImportHistoryStoreTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-10-18T10:15:30Z");

    @TempDir
    Path home;

    @Test
    void aHistoryInTheEarlierFormatIsReadWithAnEmptyVersionAsAbsent() throws Exception {
        UUID project = UUID.randomUUID();
        Path directory = Files.createDirectories(home.resolve("cli-index-history"));
        Files.writeString(directory.resolve(project + ".properties"), """
                #MINOS project index history
                #Sat Oct 18 10:15:31 UTC 2026
                completedAt=2026-10-18T10\\:15\\:30Z
                providerId=scip-java
                providerVersion=
                snapshotId=snapshot-1
                """, StandardCharsets.ISO_8859_1);

        IndexImportHistory history = new FileIndexImportHistoryStore(home).read(project).orElseThrow();

        assertEquals(new IndexImportHistory("snapshot-1", "scip-java", null, COMPLETED_AT), history);
    }

    @Test
    void aProjectWithoutHistoryReadsEmptyAndCreatesNothing() throws Exception {
        assertEquals(Optional.empty(), new FileIndexImportHistoryStore(home).read(UUID.randomUUID()));

        assertFalse(Files.exists(home.resolve("cli-index-history")));
    }

    @Test
    void aHistoryWithoutASnapshotIdIsRefusedByItsLeafName() throws Exception {
        UUID project = UUID.randomUUID();
        Path directory = Files.createDirectories(home.resolve("cli-index-history"));
        Files.writeString(directory.resolve(project + ".properties"),
                "providerId=scip-java\ncompletedAt=" + COMPLETED_AT + "\n", StandardCharsets.ISO_8859_1);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> new FileIndexImportHistoryStore(home).read(project));

        assertEquals("missing index property 'snapshotId' in " + project + ".properties", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(home.toString()), refusal.getMessage());
    }

    @Test
    void aHistoryBeyondTheByteBoundIsRefused() throws Exception {
        UUID project = UUID.randomUUID();
        Path directory = Files.createDirectories(home.resolve("cli-index-history"));
        Files.writeString(directory.resolve(project + ".properties"),
                "snapshotId=" + "s".repeat((int) FileIndexImportHistoryStore.MAX_HISTORY_BYTES) + "\n",
                StandardCharsets.ISO_8859_1);

        IOException refusal = assertThrows(IOException.class,
                () -> new FileIndexImportHistoryStore(home).read(project));

        assertTrue(refusal.getMessage().contains("exceeds byte limit"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(home.toString()), refusal.getMessage());
    }

    @Test
    void anAsciiHistoryIsWrittenAsPropertiesAndPrivate() throws Exception {
        UUID project = UUID.randomUUID();

        write(project, "snapshot-1", "1.2.3");

        Path file = file(project);
        assertEquals(expectedLines("snapshot-1", "1.2.3"), written(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    void aNonAsciiProviderVersionKeepsTheLatin1EscapedEncoding() throws Exception {
        UUID project = UUID.randomUUID();

        write(project, "snapshot-2", "é-€-1");

        Path file = file(project);
        assertEquals(expectedLines("snapshot-2", "é-€-1"), written(file));
        assertTrue(Files.readString(file, StandardCharsets.ISO_8859_1).contains("\\u00E9"),
                "a non-ASCII character is stored as a \\uXXXX escape");
        assertEquals("é-€-1", new FileIndexImportHistoryStore(home).read(project).orElseThrow().providerVersion());
    }

    @Test
    void aMissingProviderVersionIsStoredEmpty() throws Exception {
        UUID project = UUID.randomUUID();

        write(project, "snapshot-3", null);

        assertEquals(expectedLines("snapshot-3", ""), written(file(project)));
    }

    @Test
    void aSecondWriteReplacesTheFirstAndLeavesNoTemporary() throws Exception {
        UUID project = UUID.randomUUID();
        write(project, "snapshot-old", "1");

        write(project, "snapshot-new", "2");

        assertEquals(expectedLines("snapshot-new", "2"), written(file(project)));
        try (Stream<Path> siblings = Files.list(home.resolve("cli-index-history"))) {
            assertEquals(List.of(project + ".properties"),
                    siblings.map(path -> path.getFileName().toString()).toList());
        }
        assertEquals("snapshot-new", new FileIndexImportHistoryStore(home).read(project).orElseThrow().snapshotId());
    }

    @Test
    void aBlankProviderVersionIsNormalizedToAbsent() {
        assertEquals(null, new IndexImportHistory("s", "scip-java", " ", COMPLETED_AT).providerVersion());
    }

    private void write(UUID project, String snapshotId, String providerVersion) throws IOException {
        new FileIndexImportHistoryStore(home).write(project,
                new IndexImportHistory(snapshotId, "scip-java", providerVersion, COMPLETED_AT));
    }

    private Path file(UUID project) {
        return home.resolve("cli-index-history").resolve(project + ".properties");
    }

    /** The rendering of {@link Properties#store}, without the dated comment line it adds. */
    private static List<String> expectedLines(String snapshotId, String providerVersion) throws IOException {
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

    private static List<String> written(Path file) throws IOException {
        return withoutDate(Files.readString(file, StandardCharsets.ISO_8859_1));
    }
}
