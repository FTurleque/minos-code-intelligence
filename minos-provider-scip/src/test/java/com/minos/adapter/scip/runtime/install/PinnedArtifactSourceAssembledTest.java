package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.install.EmbeddedToolsCatalog.Artifact;
import com.minos.adapter.scip.runtime.install.EmbeddedToolsCatalog.Assembled;
import com.minos.adapter.scip.runtime.install.PinnedArtifactSource.EmbeddedToolIntegrityException;
import com.minos.io.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembled components (built at release time, pinned by the manifest of the distribution rather than by an
 * upstream hash) and the "does this installation ship it" questions of {@link PinnedArtifactSource}.
 */
class PinnedArtifactSourceAssembledTest {

    private static final byte[] TREE = "pretend this is an assembled tree archive".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOOL = "pretend this is a pinned tool archive".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    private String previousPayloadProperty;

    @BeforeEach
    void rememberPayloadProperty() {
        previousPayloadProperty = System.getProperty(EmbeddedToolsPayload.DIRECTORY_PROPERTY);
    }

    @AfterEach
    void restorePayloadProperty() {
        if (previousPayloadProperty == null) System.clearProperty(EmbeddedToolsPayload.DIRECTORY_PROPERTY);
        else System.setProperty(EmbeddedToolsPayload.DIRECTORY_PROPERTY, previousPayloadProperty);
    }

    private static Assembled assembled(boolean embedded) {
        return new Assembled("sample-tree", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64, "sample-provider", "1.0",
                "recipes/sample-tree.ps1", "Apache-2.0", embedded, embedded ? "assembled/sample-tree.zip" : null);
    }

    private static Artifact artifact(boolean embedded) {
        return new Artifact("sample-tool", EmbeddedToolsCatalog.PLATFORM_ANY, "1.0", "zip",
                "https://tools.example.invalid/sample-tool-1.0.zip", Sha256.hex(TOOL), TOOL.length,
                "Apache-2.0", embedded, embedded ? "artifacts/sample-tool-1.0-any.zip" : null);
    }

    private static String manifest(String treeSha256, long treeSize) {
        return "{\"formatVersion\":1,\"platform\":\"windows-x64\",\"files\":["
                + "{\"id\":\"sample-tree\",\"kind\":\"assembled\",\"version\":\"1.0\",\"path\":\"assembled/sample-tree.zip\","
                + "\"sha256\":\"" + treeSha256 + "\",\"sizeBytes\":" + treeSize + "},"
                + "{\"id\":\"sample-tool\",\"kind\":\"artifact\",\"version\":\"1.0\",\"path\":\"artifacts/sample-tool-1.0-any.zip\","
                + "\"sha256\":\"" + Sha256.hex(TOOL) + "\",\"sizeBytes\":" + TOOL.length + "}]}";
    }

    private Path payload(byte[] treeBytes, boolean writeTree, boolean writeTool) throws IOException {
        Path payload = Files.createDirectories(directory.resolve("payload"));
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), manifest(Sha256.hex(TREE), TREE.length));
        if (writeTree) {
            Files.createDirectories(payload.resolve("assembled"));
            Files.write(payload.resolve("assembled").resolve("sample-tree.zip"), treeBytes);
        }
        if (writeTool) {
            Files.createDirectories(payload.resolve("artifacts"));
            Files.write(payload.resolve("artifacts").resolve("sample-tool-1.0-any.zip"), TOOL);
        }
        return payload;
    }

    // ------------------------------------------------------------------ copyAssembled

    @Test
    void anAssembledComponentMatchingTheManifestIsCopiedVerbatim() throws IOException {
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload(TREE, true, true));
        Path partial = directory.resolve("tree.partial");

        source.copyAssembled(assembled(true), partial, 1024);

        assertArrayEquals(TREE, Files.readAllBytes(partial));
    }

    @Test
    void anAlteredAssembledComponentIsRefusedAndRemoved() throws IOException {
        byte[] altered = TREE.clone();
        altered[0] ^= 0x01;
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload(altered, true, true));
        Path partial = directory.resolve("tree.partial");

        EmbeddedToolsCatalog.Assembled component = assembled(true);

        EmbeddedToolIntegrityException refusal = assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.copyAssembled(component, partial, 1024));

        assertTrue(refusal.getMessage().contains("does not match its pinned SHA-256"), refusal.getMessage());
        assertFalse(Files.exists(partial), "a refused component must not be left on disk");
    }

    @Test
    void anAssembledComponentLargerThanTheCallerBudgetIsRefused() throws IOException {
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload(TREE, true, true));
        Path partial = directory.resolve("tree.partial");

        EmbeddedToolsCatalog.Assembled component = assembled(true);

        assertThrows(EmbeddedToolIntegrityException.class, () -> source.copyAssembled(component, partial, 8));
        assertFalse(Files.exists(partial));
    }

    @Test
    void anAssembledComponentTheManifestListedButTheDistributionLostIsARepairableRefusal() throws IOException {
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload(TREE, false, true));

        EmbeddedToolsCatalog.Assembled component = assembled(true);
        Path partial = directory.resolve("tree.partial");

        EmbeddedToolIntegrityException refusal = assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.copyAssembled(component, partial, 1024));

        assertTrue(refusal.getMessage().contains("reinstall the MINOS package"), refusal.getMessage());
    }

    @Test
    void aComponentTheManifestDoesNotListIsRefusedWithoutFallingBackToAnything() {
        PinnedArtifactSource source = PinnedArtifactSource.networkOnly();

        EmbeddedToolsCatalog.Assembled component = assembled(true);
        Path partial = directory.resolve("tree.partial");

        EmbeddedToolIntegrityException refusal = assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.copyAssembled(component, partial, 1024));

        assertTrue(refusal.getMessage().contains("does not list the embedded component sample-tree"), refusal.getMessage());
        assertFalse(Files.exists(directory.resolve("tree.partial")));
    }

    @Test
    void aMalformedPayloadRefusesAssembledComponentsToo() throws IOException {
        Path payload = payload(TREE, true, true);
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), "{ not json");
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);

        EmbeddedToolsCatalog.Assembled component = assembled(true);
        Path partial = directory.resolve("tree.partial");

        EmbeddedToolIntegrityException refusal = assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.copyAssembled(component, partial, 1024));

        assertTrue(refusal.getMessage().contains("unreadable or malformed"), refusal.getMessage());
    }

    // ------------------------------------------------------------------ shipsAssembled / ships / payload

    @Test
    void anAssembledComponentIsShippedOnlyWhenTheManifestListsIt() throws IOException {
        PinnedArtifactSource shipping = PinnedArtifactSource.forPayloadDirectory(payload(TREE, true, true));

        assertTrue(shipping.shipsAssembled(assembled(true)));
        assertFalse(shipping.shipsAssembled(assembled(false)), "a component the catalogue does not embed is never shipped");
        assertFalse(PinnedArtifactSource.networkOnly().shipsAssembled(assembled(true)));
    }

    @Test
    void aBrokenPayloadCountsAsShippingWhatItShouldHaveCarriedSoThatItIsRefused() throws IOException {
        Path payload = payload(TREE, true, true);
        Files.delete(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE));
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);

        assertTrue(source.shipsAssembled(assembled(true)));
        assertTrue(source.ships(artifact(true)));
        assertFalse(source.ships(artifact(false)), "what the catalogue does not embed is not the payload's business");
        assertTrue(source.payload().isEmpty());
    }

    @Test
    void anArtifactIsShippedOnlyWhenThePayloadCarriesItsFile() throws IOException {
        PinnedArtifactSource complete = PinnedArtifactSource.forPayloadDirectory(payload(TREE, true, true));
        assertTrue(complete.ships(artifact(true)));
        assertTrue(complete.payload().isPresent());
        assertEquals(EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64, complete.payload().orElseThrow().platform());

        assertFalse(PinnedArtifactSource.networkOnly().ships(artifact(true)));
        assertTrue(PinnedArtifactSource.networkOnly().payload().isEmpty());
    }

    @Test
    void anArtifactTheManifestListsButWhoseFileIsGoneStillCountsAsShippedSoThatItIsRefused() throws IOException {
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload(TREE, true, false));

        assertTrue(source.ships(artifact(true)));
    }

    // ------------------------------------------------------------------ forHost

    @Test
    void theHostPayloadIsTheDirectoryNamedByTheSystemProperty() throws IOException {
        Path payload = payload(TREE, true, true);
        System.setProperty(EmbeddedToolsPayload.DIRECTORY_PROPERTY, payload.toString());

        PinnedArtifactSource source = PinnedArtifactSource.forHost();

        assertTrue(source.payload().isPresent());
        assertTrue(source.shipsAssembled(assembled(true)));
    }

    @Test
    void aPayloadPropertyNamingNoDirectoryMeansNoPayloadAndNeverAGuess() {
        System.setProperty(EmbeddedToolsPayload.DIRECTORY_PROPERTY, directory.resolve("does-not-exist").toString());

        PinnedArtifactSource source = PinnedArtifactSource.forHost();

        assertTrue(source.payload().isEmpty());
        assertFalse(source.shipsAssembled(assembled(true)));
    }
}
