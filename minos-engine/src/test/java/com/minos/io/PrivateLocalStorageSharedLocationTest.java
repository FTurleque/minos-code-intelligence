package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MINOS-AUD-H23: the decision that keeps a shared location (the user profile, one of its parents, a volume root) out of
 * private storage, and the reading of {@code user.home} it relies on. Pure path logic, run on every system; the ACL
 * effect is proven by PrivateLocalStorageWindowsAclTest.
 */
class PrivateLocalStorageSharedLocationTest {

    @TempDir
    Path temporary;

    @Test
    void theProfileItsParentsAndAVolumeRootAreRefusedWithoutAPathInTheMessage() {
        Path profile = temporary.resolve("Users").resolve("someone");
        for (Path refused : List.of(profile, profile.getParent(), profile.getRoot())) {
            IOException refusal = assertThrows(IOException.class,
                    () -> PrivateLocalStorage.refuseSharedLocation(refused, profile));
            assertTrue(refusal.getMessage().contains("not dedicated to MINOS"), refusal.getMessage());
            assertTrue(!refusal.getMessage().contains(temporary.toString()), "no path in the message");
        }
    }

    @Test
    void aDirectoryUnderTheProfileOrBesideItIsAccepted() {
        Path profile = temporary.resolve("Users").resolve("someone");

        assertDoesNotThrow(() -> PrivateLocalStorage.refuseSharedLocation(profile.resolve(".minos"), profile));
        assertDoesNotThrow(() -> PrivateLocalStorage.refuseSharedLocation(profile.resolveSibling("other"), profile));
        assertDoesNotThrow(() -> PrivateLocalStorage.refuseSharedLocation(profile.resolve("a"), null));
    }

    @Test
    void anUnknownOrUnusableUserHomeGuardsNothing() throws IOException {
        Path existing = Files.createDirectories(temporary.resolve("home"));

        assertEquals(existing, PrivateLocalStorage.userHome(" " + existing + " "));
        assertNull(PrivateLocalStorage.userHome(null));
        assertNull(PrivateLocalStorage.userHome("  "));
        assertNull(PrivateLocalStorage.userHome("?"), "the JVM's placeholder for an account without a home");
        assertNull(PrivateLocalStorage.userHome("relative/home"));
        assertNull(PrivateLocalStorage.userHome(temporary.resolve("absent").toString()));
        assertNull(PrivateLocalStorage.userHome("\u0000"), "an invalid path is no home");
    }

    @Test
    void theIdentityProbeLeavesNothingBehind() throws IOException {
        Path home = Files.createDirectories(temporary.resolve("home"));

        assertEquals(Files.getOwner(home), PrivateLocalStorage.probeOwner(home));
        try (Stream<Path> left = Files.list(temporary)) {
            assertEquals(List.of(home), left.toList(), "the probe is deleted");
        }
    }

    @Test
    void aProbeThatCannotBeCreatedFailsWithoutAPath() {
        Path home = temporary.resolve("absent").resolve("home");

        IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.probeOwner(home));
        assertProbeRefusal(refusal);
        assertTrue(!(refusal.getCause() instanceof AccessDeniedException), "only the kind of failure is kept");
    }

    @Test
    void aProbeDeniedBesideAndInsideTheDirectoryFailsWithoutAPath() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        Path parent = Files.createDirectories(temporary.resolve("locked-parent"));
        Path home = Files.createDirectories(parent.resolve("locked-home"));
        Set<PosixFilePermission> readOnly = PosixFilePermissions.fromString("r-xr-xr-x");
        Files.setPosixFilePermissions(home, readOnly);
        Files.setPosixFilePermissions(parent, readOnly);
        try {
            assumeTrue(!Files.isWritable(parent), "an account that ignores permissions (root) cannot be denied");

            IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.probeOwner(home));
            assertProbeRefusal(refusal);
            assertTrue(refusal.getCause() instanceof AccessDeniedException, String.valueOf(refusal.getCause()));
        } finally {
            Set<PosixFilePermission> writable = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(parent, writable);
            Files.setPosixFilePermissions(home, writable);
        }
    }

    private void assertProbeRefusal(IOException refusal) {
        assertTrue(refusal.getMessage().contains("cannot verify who owns"), refusal.getMessage());
        for (Throwable link = refusal; link != null; link = link.getCause()) {
            assertTrue(link.getMessage() == null || !link.getMessage().contains(temporary.toString()),
                    "no path in " + link);
        }
    }
}
