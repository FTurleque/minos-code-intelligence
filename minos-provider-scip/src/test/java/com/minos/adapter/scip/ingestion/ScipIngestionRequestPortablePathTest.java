package com.minos.adapter.scip.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A path that is rooted on one platform is refused on every platform: the host the ingestion runs on must not
 * decide whether {@code /absolute} or {@code C:/x} stays inside the registered project.
 */
class ScipIngestionRequestPortablePathTest {

    /** POSIX root, Windows root-relative, drive-qualified (absolute and drive-relative) and UNC forms. */
    private static final List<String> ROOTED_ON_SOME_PLATFORM = List.of(
            "/absolute", "\\absolute", "C:/x", "C:\\x", "c:x", "//server/share", "\\\\server\\share");

    private static ScipIngestionRequest request(String root) {
        return new ScipIngestionRequest("project", null, "scip-java", null, null, null, root);
    }

    @Test
    void aRootThatIsRootedOnAnyPlatformIsRefusedOnEveryPlatform() {
        for (String root : ROOTED_ON_SOME_PLATFORM) {
            assertThrows(IllegalArgumentException.class, () -> request(root), root);
        }
    }

    @Test
    void aRootThatLeavesTheProjectOnlyOnceNormalisedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> request("./../outside"));
        assertThrows(IllegalArgumentException.class, () -> request("services/../../outside"));
    }

    @Test
    void aDriveLetterRevealedOnlyByNormalisationIsRefusedToo() {
        ScipIngestionRequest rooted = request("services/api");

        for (String path : List.of("./C:/x", "x/../C:/x", ".\\C:\\x", "./c:x")) {
            assertThrows(IllegalArgumentException.class, () -> request(path), path);
            assertEquals(path, rooted.projectRelativePath(path), "never prefixed by the root");
        }
    }

    @Test
    void aProviderPathThatIsRootedOnAnyPlatformIsNeverPrefixedByTheRoot() {
        ScipIngestionRequest rooted = request("services/api");
        ScipIngestionRequest atProjectRoot = request("");

        for (String providerPath : ROOTED_ON_SOME_PLATFORM) {
            assertEquals(providerPath, rooted.projectRelativePath(providerPath),
                    "a path the request cannot place is returned as the provider wrote it");
            assertEquals(providerPath, atProjectRoot.projectRelativePath(providerPath), providerPath);
        }
    }

    @Test
    void relativePathsKeepTheirPortableForm() {
        assertEquals("services/api", request("services\\api").projectRelativeRoot());
        assertEquals("c/x", request("c/x").projectRelativeRoot(), "a one-letter directory is not a drive");
        assertEquals("services/api/c/A.java", request("services/api").projectRelativePath("c\\A.java"));
        assertEquals("services/api/src/A.java", request("services/api").projectRelativePath("./src/A.java"));
    }
}
