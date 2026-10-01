package com.minos.adapter.scip.runtime;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.PrivateLocalStorage.Privacy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S5: the roots where MINOS stages snapshots and prepares tool installs under MINOS_HOME hold material
 * derived from the user's code, so they are owner-only, not left to the defaults of the platform.
 */
class ScipRuntimeStoragePrivacyTest {

    private static void assertPrivate(Path path) throws Exception {
        assertEquals(Privacy.ENFORCED, PrivateLocalStorage.privacyOf(path), "not owner-only: " + path.getFileName());
    }

    @Test
    void theStagingAndRunsRootsOfTheSnapshotLifecycleAreOwnerOnly(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");

        new ScipProjectSnapshotLifecycle(home);

        assertPrivate(home.resolve("staged-snapshots"));
        assertPrivate(home.resolve("runs"));
    }

    @Test
    void aLockedNpmInstallRootAndItsPackageManifestAreOwnerOnly(@TempDir Path root) throws Exception {
        Path installRoot = root.resolve("tools").resolve("scip-typescript.partial");

        try {
            LockedNpmPackage.prepare(
                    ManagedScipProviderRuntimeManager.class, installRoot,
                    "scip-typescript-package-lock.json",
                    "@sourcegraph/scip-typescript",
                    ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION,
                    "sha512-not-the-pinned-integrity");
        } catch (java.io.IOException | RuntimeException integrityMismatchIsNotTheSubject) {
            // The fixture only needs the root and the manifest to exist; verification is covered elsewhere.
        }

        assertPrivate(installRoot);
        assertPrivate(installRoot.resolve("package.json"));
        assertEquals(true, Files.isRegularFile(installRoot.resolve("package.json")));
    }
}
