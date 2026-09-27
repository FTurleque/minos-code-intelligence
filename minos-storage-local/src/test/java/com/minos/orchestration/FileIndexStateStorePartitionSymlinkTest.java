package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FileIndexStateStorePartitionSymlinkTest {

    @Test
    void listRunsRejectsSymlinkedProjectPartition(@TempDir Path temp) throws Exception {
        Path stateRoot = temp.resolve("index-state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        UUID projectId = UUID.randomUUID();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(stateRoot.resolve("runs").resolve(projectId.toString()), outside)) return;

        assertThrows(UncheckedIOException.class, () -> store.listRuns(projectId));
    }

    @Test
    void locatorMigrationRejectsSymlinkedProjectPartition(@TempDir Path temp) throws Exception {
        Path stateRoot = temp.resolve("index-state");
        new FileIndexStateStore(stateRoot);
        Files.deleteIfExists(stateRoot.resolve("runs/.by-id/v1.ready"));
        UUID projectId = UUID.randomUUID();
        Path outside = Files.createDirectories(temp.resolve("outside-migration"));
        if (!createSymbolicLink(stateRoot.resolve("runs").resolve(projectId.toString()), outside)) return;

        assertThrows(IOException.class, () -> new FileIndexStateStore(stateRoot));
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
