package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalProjectRegistrySymlinkTest {

    @Test
    void directRegistryConstructionRejectsSymlinkedStorageRoot(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path root = temp.resolve("registry");
        if (!createSymbolicLink(root, outside)) return;

        assertThrows(IOException.class, () -> new LocalProjectRegistry(root));
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
