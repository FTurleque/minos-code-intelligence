package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
