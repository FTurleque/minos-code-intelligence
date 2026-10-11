package com.minos.adapter.scip.runtime.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tools shipped next to an installation are never trusted by birth: a payload whose manifest is absent or
 * malformed, a listed file that is missing, or a path that leaves the payload directory is a refusal, not a
 * best-effort read.
 */
class EmbeddedToolsPayloadRefusalTest {

    private static final String SHA = "b".repeat(64);

    @TempDir
    Path temp;

    private Path payload(String manifest) throws IOException {
        Path directory = Files.createDirectories(temp.resolve("tools"));
        if (manifest != null) {
            Files.writeString(directory.resolve(EmbeddedToolsPayload.MANIFEST_FILE), manifest);
        }
        return directory;
    }

    private static String manifest(String files) {
        return "{\"formatVersion\":1,\"platform\":\"windows-x64\",\"files\":" + files + "}";
    }

    private static String entry(String id, String kind, String path) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"version\":\"1\",\"path\":\"" + path
                + "\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":3}";
    }

    private static EmbeddedToolsCatalog.Artifact artifact(String platform, boolean embedded, String payloadPath) {
        return new EmbeddedToolsCatalog.Artifact("maven", platform, "3.9.16", "zip", "https://repo.example/maven.zip",
                "a".repeat(64), 3L, "Apache-2.0", embedded, payloadPath);
    }

    @Test
    void aPayloadWithoutAManifestIsRefused() throws Exception {
        Path directory = payload(null);

        IOException refusal = assertThrows(IOException.class, () -> EmbeddedToolsPayload.open(directory));

        assertTrue(refusal.getMessage().contains(EmbeddedToolsPayload.MANIFEST_FILE), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), "no absolute path in the refusal");
    }

    @Test
    void aManifestOfAnotherFormatWithoutPlatformOrWithoutFilesIsRefused() throws Exception {
        for (String broken : List.of(
                "{\"formatVersion\":2,\"platform\":\"windows-x64\",\"files\":[]}",
                "{\"platform\":\"windows-x64\",\"files\":[]}",
                "{\"formatVersion\":1,\"platform\":\" \",\"files\":[]}",
                "{\"formatVersion\":1,\"files\":[]}",
                "{\"formatVersion\":1,\"platform\":\"windows-x64\"}",
                "{\"formatVersion\":1,\"platform\":\"windows-x64\",\"files\":{}}")) {
            Path directory = payload(broken);
            assertThrows(IOException.class, () -> EmbeddedToolsPayload.open(directory), broken);
        }
    }

    @Test
    void aManifestEntryWithoutHashPathOrIdentifierIsRefused() throws Exception {
        for (String broken : List.of(
                "[{\"id\":\"maven\",\"path\":\"artifacts/maven.zip\",\"sha256\":\"abc\"}]",
                "[{\"id\":\"maven\",\"path\":\"artifacts/maven.zip\",\"sha256\":\"" + "B".repeat(64) + "\"}]",
                "[{\"id\":\"maven\",\"path\":\" \",\"sha256\":\"" + SHA + "\"}]",
                "[{\"id\":\" \",\"path\":\"artifacts/maven.zip\",\"sha256\":\"" + SHA + "\"}]",
                "[{\"path\":\"artifacts/maven.zip\",\"sha256\":\"" + SHA + "\"}]")) {
            Path directory = payload(manifest(broken));
            IOException refusal = assertThrows(IOException.class, () -> EmbeddedToolsPayload.open(directory), broken);
            assertTrue(refusal.getMessage().contains("malformed entry"), refusal.getMessage());
        }
    }

    @Test
    void aWellFormedPayloadAnswersItsEntriesByIdentifierAndKind() throws Exception {
        Path directory = payload(manifest("[" + entry("maven", "artifact", "artifacts/maven.zip") + ","
                + "{\"id\":\"node\",\"path\":\"artifacts/node.zip\",\"sha256\":\"" + SHA + "\"}]"));
        Files.createDirectories(directory.resolve("artifacts"));
        Files.writeString(directory.resolve("artifacts/maven.zip"), "zip");

        EmbeddedToolsPayload payload = EmbeddedToolsPayload.open(directory);

        assertEquals("windows-x64", payload.platform());
        assertTrue(payload.provides("maven"));
        assertFalse(payload.provides("coursier"));
        EmbeddedToolsPayload.Entry maven = payload.entry("maven", EmbeddedToolsPayload.KIND_ARTIFACT).orElseThrow();
        assertEquals(3L, maven.sizeBytes());
        assertTrue(payload.entry("maven", EmbeddedToolsPayload.KIND_ASSEMBLED).isEmpty(), "the kind is part of the key");
        EmbeddedToolsPayload.Entry node = payload.entry("node", EmbeddedToolsPayload.KIND_ARTIFACT).orElseThrow();
        assertEquals(-1L, node.sizeBytes(), "a size the manifest does not record is not invented");
        assertEquals(directory.resolve("artifacts/maven.zip").toAbsolutePath().normalize(), payload.file(maven));
        try (var input = EmbeddedToolsPayload.openNoFollow(payload.file(maven))) {
            assertEquals("zip", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void aListedFileThatIsMissingOrIsADirectoryIsRefused() throws Exception {
        Path directory = payload(manifest("[" + entry("maven", "artifact", "artifacts/maven.zip") + ","
                + entry("node", "artifact", "artifacts") + "]"));
        Files.createDirectories(directory.resolve("artifacts"));
        EmbeddedToolsPayload payload = EmbeddedToolsPayload.open(directory);

        IOException missing = assertThrows(IOException.class,
                () -> payload.file(payload.entry("maven", EmbeddedToolsPayload.KIND_ARTIFACT).orElseThrow()));
        IOException directoryEntry = assertThrows(IOException.class,
                () -> payload.file(payload.entry("node", EmbeddedToolsPayload.KIND_ARTIFACT).orElseThrow()));

        assertTrue(missing.getMessage().contains("maven"), missing.getMessage());
        assertFalse(missing.getMessage().contains(temp.toString()), "no absolute path in the refusal");
        assertTrue(directoryEntry.getMessage().contains("node"), directoryEntry.getMessage());
    }

    @Test
    void aPathThatLeavesThePayloadOrNamesItsRootIsRefused() throws Exception {
        Path directory = payload(manifest("[" + entry("escape", "artifact", "../outside.zip") + ","
                + entry("root", "artifact", ".") + "," + entry("maven", "artifact", "artifacts/maven.zip") + "]"));
        Files.writeString(temp.resolve("outside.zip"), "outside");
        EmbeddedToolsPayload payload = EmbeddedToolsPayload.open(directory);

        for (String id : List.of("escape", "root")) {
            IOException refusal = assertThrows(IOException.class,
                    () -> payload.file(payload.entry(id, EmbeddedToolsPayload.KIND_ARTIFACT).orElseThrow()), id);
            assertTrue(refusal.getMessage().contains("outside the payload"), refusal.getMessage());
        }
        String absolute = temp.resolve("outside.zip").toAbsolutePath().toString();
        assertThrows(IOException.class, () -> payload.artifactFile(artifact("any", true, absolute)));
    }

    @Test
    void anArtifactIsServedOnlyWhenEmbeddedForThisPlatformAndListedByThePayload() throws Exception {
        Path directory = payload(manifest("[" + entry("maven", "artifact", "artifacts/maven.zip") + "]"));
        Files.createDirectories(directory.resolve("artifacts"));
        EmbeddedToolsPayload payload = EmbeddedToolsPayload.open(directory);

        assertEquals(Optional.empty(), payload.artifactFile(artifact("any", false, null)), "not embedded");
        assertEquals(Optional.empty(), payload.artifactFile(artifact("linux-x64", true, "artifacts/maven.zip")),
                "another platform");
        EmbeddedToolsCatalog.Artifact unlisted = new EmbeddedToolsCatalog.Artifact("coursier", "any", "1", "zip",
                "https://repo.example/cs.zip", "a".repeat(64), 3L, "Apache-2.0", true, "artifacts/cs.zip");
        assertEquals(Optional.empty(), payload.artifactFile(unlisted), "the payload does not list it");

        IOException missing = assertThrows(IOException.class,
                () -> payload.artifactFile(artifact("windows-x64", true, "artifacts/maven.zip")));
        assertTrue(missing.getMessage().contains("maven"), missing.getMessage());

        Files.writeString(directory.resolve("artifacts/maven.zip"), "zip");
        assertTrue(payload.artifactFile(artifact("any", true, "artifacts/maven.zip")).isPresent());
        assertTrue(payload.artifactFile(artifact("windows-x64", true, "artifacts/maven.zip")).isPresent());
    }

    @Test
    void theConfiguredPayloadDirectoryMustExistAndABlankSettingIsIgnored() throws Exception {
        String property = EmbeddedToolsPayload.DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        String previousLauncher = System.getProperty("jpackage.app-path");
        Path directory = payload(manifest("[]"));
        try {
            System.setProperty(property, directory.toString());
            assertEquals(Optional.of(directory.toAbsolutePath().normalize()), EmbeddedToolsPayload.locateDirectory());
            System.setProperty(property, temp.resolve("absent").toString());
            assertEquals(Optional.empty(), EmbeddedToolsPayload.locateDirectory(), "a configured directory that does not exist");

            // Without an explicit setting the payload sits beside the packaged launcher: <installation>/tools.
            System.clearProperty(property);
            if (System.getenv(EmbeddedToolsPayload.DIRECTORY_VARIABLE) == null) {
                Path launcher = Files.createDirectories(temp.resolve("app")).resolve("minos.exe");
                System.setProperty("jpackage.app-path", launcher.toString());
                assertEquals(Optional.of(directory.toAbsolutePath().normalize()), EmbeddedToolsPayload.locateDirectory());
                System.setProperty("jpackage.app-path", " ");
                assertEquals(Optional.empty(), EmbeddedToolsPayload.locateDirectory());
            }
        } finally {
            restore(property, previous);
            restore("jpackage.app-path", previousLauncher);
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
