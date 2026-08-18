package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedFileLeaseSymlinkTest {

    @Test
    void rejectsPreexistingSymlinkLockFile(@TempDir Path temp) throws Exception {
        Path target = Files.writeString(temp.resolve("outside.lock"), "outside");
        Path lock = temp.resolve("lease.lock");
        if (!createSymbolicLink(lock, target)) return;

        assertThrows(IOException.class, () -> BoundedFileLease.acquire(
                lock, new ReentrantLock(), Duration.ofMillis(250), "symlink fixture"));
    }

    @Test
    void createsOwnerOnlyRegularLockFile(@TempDir Path temp) throws Exception {
        Path lock = temp.resolve("lease.lock");
        try (BoundedFileLease ignored = BoundedFileLease.acquire(
                lock, new ReentrantLock(), Duration.ofSeconds(1), "private fixture")) {
            assertFalse(Files.isSymbolicLink(lock));
            assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(lock));
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
