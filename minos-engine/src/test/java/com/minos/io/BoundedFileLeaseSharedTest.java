package com.minos.io;

import com.minos.testsupport.WriteProtectedTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Le bail d'un lecteur : rien n'est créé, durci ni ouvert en écriture ; un verrou non conforme est refusé. */
class BoundedFileLeaseSharedTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path root;

    @Test
    void aReaderTakesTheLeaseOnAWriteProtectedLockFileAndLeavesItUntouched() throws Exception {
        Path directory = Files.createDirectory(root.resolve("storage"));
        Path lockFile = directory.resolve("lease.lock");
        ReentrantLock jvmLock = new ReentrantLock();
        BoundedFileLease.acquire(lockFile, jvmLock, TIMEOUT, "writer lease").close();

        try (WriteProtectedTree protectedStorage = WriteProtectedTree.protect(directory)) {
            Map<String, String> before = protectedStorage.state();

            try (BoundedFileLease ignored = BoundedFileLease.acquireShared(lockFile, jvmLock, TIMEOUT, "reader lease")) {
                assertTrue(jvmLock.isHeldByCurrentThread());
            }

            assertFalse(jvmLock.isLocked());
            assertEquals(before, protectedStorage.state(), "a reader must not open, harden or create anything");
        }
    }

    @Test
    void aReaderDoesNotCreateAnAbsentLockFile() throws Exception {
        Path lockFile = root.resolve("lease.lock");
        ReentrantLock jvmLock = new ReentrantLock();

        BoundedFileLease lease = BoundedFileLease.acquireShared(lockFile, jvmLock, TIMEOUT, "reader lease");
        assertTrue(jvmLock.isHeldByCurrentThread());
        lease.close();
        lease.close();

        assertFalse(jvmLock.isLocked());
        assertFalse(Files.exists(lockFile), "a reader never creates the lock file");
    }

    @Test
    void aReaderRefusesASymbolicLinkWithoutNamingAnyPath() throws Exception {
        Path target = Files.writeString(root.resolve("outside.lock"), "outside");
        Path lockFile = root.resolve("lease.lock");
        try {
            Files.createSymbolicLink(lockFile, target);
        } catch (UnsupportedOperationException | IOException noSymbolicLinks) {
            assumeTrue(false, "this account cannot create a symbolic link");
        }
        ReentrantLock jvmLock = new ReentrantLock();

        IOException failure = assertThrows(IOException.class,
                () -> BoundedFileLease.acquireShared(lockFile, jvmLock, TIMEOUT, "reader lease"));

        assertFalse(failure.getMessage().contains(root.toString()), failure.getMessage());
        assertFalse(jvmLock.isLocked(), "a refused reader holds nothing");
    }

    /** Sous Windows le refus d'une entrée ACL étrangère est celui de {@code PrivateLocalStorage}, déjà testé. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aReaderRefusesALockFileReadableBeyondItsOwnerAndDoesNotRepairIt() throws Exception {
        Path lockFile = root.resolve("lease.lock");
        ReentrantLock jvmLock = new ReentrantLock();
        BoundedFileLease.acquire(lockFile, jvmLock, TIMEOUT, "writer lease").close();
        Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-r--r--"));

        IOException failure = assertThrows(IOException.class,
                () -> BoundedFileLease.acquireShared(lockFile, jvmLock, TIMEOUT, "reader lease"));

        assertFalse(failure.getMessage().contains(root.toString()), failure.getMessage());
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(lockFile)),
                "a reader refuses a lock file that is not private, it does not harden it");
        assertFalse(jvmLock.isLocked());
    }
}
