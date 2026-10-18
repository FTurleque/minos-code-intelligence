package com.minos.adapter.scip.runtime.install;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lockfile prepared for an npm install (AUD-SEC-03) is written by the hardening stream: a lockfile which already
 * exists is replaced and made owner-only, and one which is a link is refused instead of followed.
 */
class LockedNpmPackageLockfileWriteTest {

    private static final String RESOURCE = EmbeddedToolsCatalog.RESOURCE_ROOT + "scip-typescript-package-lock.json";
    private static final String PACKAGE = "@sourcegraph/scip-typescript";
    private static final String VERSION = "0.4.0";
    private static final String INTEGRITY =
            "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==";

    private static byte[] packagedLock() throws IOException {
        try (InputStream input = LockedNpmPackageLockfileWriteTest.class.getResourceAsStream(RESOURCE)) {
            return input.readAllBytes();
        }
    }

    private static void prepare(Path installRoot) throws IOException {
        LockedNpmPackage.prepare(LockedNpmPackageLockfileWriteTest.class, installRoot, RESOURCE, PACKAGE, VERSION,
                INTEGRITY);
    }

    @Test
    void theLockfileIsWrittenAsPackagedAndOwnerOnly(@TempDir Path temporary) throws Exception {
        Path root = temporary.resolve("install");

        prepare(root);

        assertArrayEquals(packagedLock(), Files.readAllBytes(root.resolve("package-lock.json")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(root.resolve("package-lock.json")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(root.resolve("package.json")));
    }

    @Test
    void anExistingLockfileOfAnotherContentIsReplacedAndHardened(@TempDir Path temporary) throws Exception {
        Path root = temporary.resolve("install");
        PrivateLocalStorage.ensurePrivateDirectory(root);
        Files.writeString(root.resolve("package-lock.json"), "{\"stale\": true}");

        prepare(root);

        assertArrayEquals(packagedLock(), Files.readAllBytes(root.resolve("package-lock.json")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(root.resolve("package-lock.json")));
    }

    @Test
    void aLockfileReplacedByALinkIsRefusedAndTheLinkTargetIsLeftIntact(@TempDir Path temporary) throws Exception {
        Path sentinel = temporary.resolve("sentinel.json");
        Files.writeString(sentinel, "untouched");
        Path root = temporary.resolve("install");
        PrivateLocalStorage.ensurePrivateDirectory(root);
        try {
            Files.createSymbolicLink(root.resolve("package-lock.json"), sentinel.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.abort("symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }

        assertThrows(IOException.class, () -> prepare(root));

        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void aMissingPackagedLockfileIsRefused(@TempDir Path temporary) {
        assertThrows(IOException.class, () -> LockedNpmPackage.prepare(LockedNpmPackageLockfileWriteTest.class,
                temporary.resolve("install"), EmbeddedToolsCatalog.RESOURCE_ROOT + "no-such-lock.json", PACKAGE,
                VERSION, INTEGRITY));
    }
}
