package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FileIndexStateStoreSymlinkTest {

    @Test
    void rejectsSymlinkedStorageRoot(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path stateRoot = temp.resolve("index-state");
        if (!createSymbolicLink(stateRoot, outside)) return;

        assertThrows(IOException.class, () -> new FileIndexStateStore(stateRoot));
    }

    @Test
    void rejectsSymlinkedProjectStateLeaf(@TempDir Path temp) throws Exception {
        Path stateRoot = temp.resolve("index-state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        UUID projectId = UUID.randomUUID();
        store.saveProjectState(ProjectIndexState.neverIndexed(projectId, Instant.EPOCH));

        Path state = stateRoot.resolve("projects").resolve(projectId + ".properties");
        Path outside = temp.resolve("outside.properties");
        Files.move(state, outside, StandardCopyOption.REPLACE_EXISTING);
        if (!createSymbolicLink(state, outside)) return;

        assertThrows(UncheckedIOException.class, () -> store.findProjectState(projectId));
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
