package com.minos.adapter.scip.runtime.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The npm lockfile MINOS installs from pins the root package by version and integrity: any lockfile that names
 * another version, another integrity, another lock format, or no such package is refused before npm runs.
 */
class LockedNpmPackageRefusalTest {

    private static final String PACKAGE = "@sourcegraph/scip-typescript";
    private static final String INTEGRITY = "sha512-AAAA";

    @TempDir
    Path temp;

    private Path lock(String content) throws IOException {
        Path file = temp.resolve("package-lock.json");
        Files.writeString(file, content);
        return file;
    }

    private static String lockfile(String version, String integrity, String lockfileVersion) {
        return "{\n  \"lockfileVersion\": " + lockfileVersion + ",\n  \"packages\": {\n"
                + "    \"node_modules/" + PACKAGE + "\": {\n      \"version\": \"" + version + "\",\n"
                + "      \"integrity\": \"" + integrity + "\"\n    },\n"
                + "    \"node_modules/other\": {\n      \"version\": \"9.9.9\",\n      \"integrity\": \"sha512-OTHER\"\n    }\n  }\n}\n";
    }

    @Test
    void theLockfileThatPinsTheRootPackageIsAccepted() throws Exception {
        Path file = lock(lockfile("0.4.0", INTEGRITY, "3"));

        assertDoesNotThrow(() -> LockedNpmPackage.verify(file, PACKAGE, "0.4.0", INTEGRITY));
    }

    @Test
    void aMissingEmptyOrDirectoryLockfileIsRefused() throws Exception {
        Path absent = temp.resolve("absent.json");
        Path empty = lock("");
        Path directory = Files.createDirectories(temp.resolve("as-directory"));

        assertThrows(IOException.class, () -> LockedNpmPackage.verify(absent, PACKAGE, "0.4.0", INTEGRITY));
        IOException emptyRefusal = assertThrows(IOException.class, () -> LockedNpmPackage.verify(empty, PACKAGE, "0.4.0", INTEGRITY));
        assertThrows(IOException.class, () -> LockedNpmPackage.verify(directory, PACKAGE, "0.4.0", INTEGRITY));
        assertTrue(emptyRefusal.getMessage().contains("size is invalid"), emptyRefusal.getMessage());
    }

    @Test
    void aLockfileWithoutTheRootPackageIsRefused() throws Exception {
        Path file = lock("{\n  \"lockfileVersion\": 3,\n  \"packages\": {}\n}\n");

        IOException refusal = assertThrows(IOException.class, () -> LockedNpmPackage.verify(file, PACKAGE, "0.4.0", INTEGRITY));

        assertTrue(refusal.getMessage().contains("does not contain root package"), refusal.getMessage());
    }

    @Test
    void anotherVersionIntegrityOrLockFormatIsRefused() throws Exception {
        Path otherVersion = lock(lockfile("0.4.1", INTEGRITY, "3"));
        IOException version = assertThrows(IOException.class, () -> LockedNpmPackage.verify(otherVersion, PACKAGE, "0.4.0", INTEGRITY));
        assertTrue(version.getMessage().contains("version mismatch"), version.getMessage());

        Path otherIntegrity = lock(lockfile("0.4.0", "sha512-TAMPERED", "3"));
        IOException integrity = assertThrows(IOException.class, () -> LockedNpmPackage.verify(otherIntegrity, PACKAGE, "0.4.0", INTEGRITY));
        assertTrue(integrity.getMessage().contains("integrity mismatch"), integrity.getMessage());

        Path otherFormat = lock(lockfile("0.4.0", INTEGRITY, "2"));
        IOException format = assertThrows(IOException.class, () -> LockedNpmPackage.verify(otherFormat, PACKAGE, "0.4.0", INTEGRITY));
        assertTrue(format.getMessage().contains("lockfileVersion 3"), format.getMessage());
    }

    @Test
    void theIntegrityOfAnotherPackageDoesNotStandForTheRootPackage() throws Exception {
        // The root entry carries a wrong integrity; the expected one only appears on a later, unrelated package.
        Path file = lock(lockfile("0.4.0", "sha512-WRONG", "3").replace("sha512-OTHER", INTEGRITY));

        assertThrows(IOException.class, () -> LockedNpmPackage.verify(file, PACKAGE, "0.4.0", INTEGRITY));
    }

    @Test
    void theRootPackageMayBeTheLastEntryOfTheLockfile() throws Exception {
        Path file = lock("{\n  \"lockfileVersion\": 3,\n  \"packages\": {\n    \"node_modules/" + PACKAGE
                + "\": {\n      \"version\": \"0.4.0\",\n      \"integrity\": \"" + INTEGRITY + "\"\n    }\n  }\n}\n");

        assertDoesNotThrow(() -> LockedNpmPackage.verify(file, PACKAGE, "0.4.0", INTEGRITY));
    }

    @Test
    void preparingAnInstallationRequiresEveryArgumentAndAPackagedLockfile() {
        Path root = temp.resolve("install");

        assertThrows(NullPointerException.class,
                () -> LockedNpmPackage.prepare(null, root, "lock.json", PACKAGE, "0.4.0", INTEGRITY));
        assertThrows(NullPointerException.class,
                () -> LockedNpmPackage.prepare(LockedNpmPackage.class, null, "lock.json", PACKAGE, "0.4.0", INTEGRITY));
        for (String[] blank : new String[][] {
                {" ", PACKAGE, "0.4.0", INTEGRITY}, {"lock.json", " ", "0.4.0", INTEGRITY},
                {"lock.json", PACKAGE, " ", INTEGRITY}, {"lock.json", PACKAGE, "0.4.0", " "}}) {
            assertThrows(RuntimeException.class,
                    () -> LockedNpmPackage.prepare(LockedNpmPackage.class, root, blank[0], blank[1], blank[2], blank[3]));
        }
        IOException missing = assertThrows(IOException.class,
                () -> LockedNpmPackage.prepare(LockedNpmPackage.class, root, "no-such-lock.json", PACKAGE, "0.4.0", INTEGRITY));
        assertTrue(missing.getMessage().contains("packaged npm lockfile is missing"), missing.getMessage());
    }
}
