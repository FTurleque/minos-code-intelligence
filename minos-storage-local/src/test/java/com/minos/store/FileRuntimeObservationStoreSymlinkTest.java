package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FileRuntimeObservationStoreSymlinkTest {

    @Test
    void rejectsSymlinkedStoreRoot(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path root = temp.resolve("runtime-observations");
        if (!createSymbolicLink(root, outside)) return;

        assertThrows(IOException.class, () -> new FileRuntimeObservationStore(root));
    }

    @Test
    void rejectsSymlinkedProjectLockLeaf(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("runtime-observations");
        FileRuntimeObservationStore store = new FileRuntimeObservationStore(root);
        UUID projectId = UUID.randomUUID();
        Path project = Files.createDirectories(root.resolve(projectId.toString()));
        Path outside = Files.writeString(temp.resolve("outside.lock"), "outside");
        if (!createSymbolicLink(project.resolve(".lock"), outside)) return;

        assertThrows(IOException.class, () -> store.list(projectId));
    }

    @Test
    void rejectsSymlinkedSessionLeaf(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("runtime-observations");
        FileRuntimeObservationStore store = new FileRuntimeObservationStore(root);
        UUID projectId = UUID.randomUUID();
        Path project = Files.createDirectories(root.resolve(projectId.toString()));
        Path outside = Files.writeString(temp.resolve("outside.mrt"), "outside");
        if (!createSymbolicLink(project.resolve("session-fixture.mrt"), outside)) return;

        assertThrows(IOException.class, () -> store.list(projectId));
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
