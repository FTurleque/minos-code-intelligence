package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectIndexLeaseSymlinkTest {

    @Test
    void rejectsSymlinkedLockRoot(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(home.resolve("locks"), outside)) return;

        assertThrows(IOException.class, () -> ProjectIndexLease.acquire(home, UUID.randomUUID()));
    }

    @Test
    void rejectsSymlinkedIndexingDirectory(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.createDirectories(home.resolve("locks"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(home.resolve("locks/indexing"), outside)) return;

        assertThrows(IOException.class, () -> ProjectIndexLease.acquire(home, UUID.randomUUID()));
    }

    @Test
    void rejectsSymlinkedProjectLockLeaf(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path indexing = Files.createDirectories(home.resolve("locks/indexing"));
        UUID projectId = UUID.randomUUID();
        Path outside = Files.writeString(temp.resolve("outside.lock"), "outside");
        if (!createSymbolicLink(indexing.resolve(projectId + ".lock"), outside)) return;

        assertThrows(IOException.class, () -> ProjectIndexLease.acquire(home, projectId));
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
