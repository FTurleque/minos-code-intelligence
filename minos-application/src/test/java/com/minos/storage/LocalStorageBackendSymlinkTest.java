package com.minos.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageBackendSymlinkTest {

    @Test
    void rejectsEverySymlinkedImmediateStorageNamespace(@TempDir Path temp) throws Exception {
        for (String namespace : List.of(
                "registry",
                "symbol-snapshots",
                "index-state",
                "fingerprint-snapshots",
                "semantic-index",
                "runtime-observations")) {
            Path fixture = temp.resolve(namespace);
            Path home = Files.createDirectories(fixture.resolve("home"));
            Path outside = Files.createDirectories(fixture.resolve("outside"));
            if (!createSymbolicLink(home.resolve(namespace), outside)) return;

            IOException rejected = assertThrows(IOException.class, () -> new LocalStorageBackend(home));
            assertTrue(rejected.getMessage().contains("symbolic link"),
                    () -> namespace + " was rejected for the wrong reason: " + rejected.getMessage());
        }
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }
}
