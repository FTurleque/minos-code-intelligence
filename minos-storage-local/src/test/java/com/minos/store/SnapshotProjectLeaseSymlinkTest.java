package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotProjectLeaseSymlinkTest {

    @Test
    void rejectsSymlinkedMutationLeaseDirectory(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path storageRoot = Files.createDirectories(home.resolve("symbol-snapshots"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(home.resolve(".project-mutation-leases"), outside)) return;

        assertThrows(IOException.class, () -> SnapshotProjectLease.acquire(storageRoot, "project-1"));
    }

    @Test
    void rejectsSymlinkedMutationLeaseLeaf(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path storageRoot = Files.createDirectories(home.resolve("symbol-snapshots"));
        Path leases = Files.createDirectories(home.resolve(".project-mutation-leases"));
        Path outside = Files.writeString(temp.resolve("outside.lock"), "outside");
        if (!createSymbolicLink(leases.resolve("project-1.lock"), outside)) return;

        assertThrows(IOException.class, () -> SnapshotProjectLease.acquire(storageRoot, "project-1"));
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
