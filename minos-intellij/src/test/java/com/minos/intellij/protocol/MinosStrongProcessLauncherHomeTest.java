package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H23: the launcher replaced the whole DACL of the MINOS directory with one entry for its owner, whoever
 * owned it. With the user profile as MINOS directory (owned by SYSTEM), the profile became SYSTEM-only and no JVM could
 * start. These tests never touch the real profile: {@code user.home} is redirected to a temporary directory.
 */
class MinosStrongProcessLauncherHomeTest {

    @TempDir
    Path temp;

    @Test
    void theProfileItsAncestorsAndAVolumeRootAreRefusedAsMinosDirectory() {
        Path home = temp.resolve("Users").resolve("someone");

        for (Path refused : List.of(home, home.getParent(), home.getRoot())) {
            IOException failure = assertThrows(IOException.class,
                    () -> MinosStrongProcessLauncher.refuseSharedLocation(refused, home, "MINOS ownership home"));
            assertTrue(failure.getMessage().startsWith("MINOS ownership home must be a directory dedicated to MINOS"),
                    failure.getMessage());
            assertFalse(failure.getMessage().contains(temp.toString()), failure.getMessage());
        }
        assertDoesNotThrow(() -> MinosStrongProcessLauncher.refuseSharedLocation(
                home.resolve(".minos"), home, "MINOS ownership home"));
        assertDoesNotThrow(() -> MinosStrongProcessLauncher.refuseSharedLocation(
                home.resolveSibling("someone-else"), home, "MINOS ownership home"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aMinosDirectoryConfiguredOnTheProfileIsRefusedAndItsAclIsLeftAlone() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("profile"));
        List<AclEntry> before = acl(profile);
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", profile.toString());
        try {
            IOException failure = assertThrows(IOException.class,
                    () -> MinosStrongProcessLauncher.start(sleepingProcess(), profile.toString()));
            // Windows paths ignore case: the same profile written in upper case is the same profile.
            assertThrows(IOException.class, () -> MinosStrongProcessLauncher.start(
                    sleepingProcess(), profile.toString().toUpperCase(java.util.Locale.ROOT)));

            assertTrue(failure.getMessage().contains("must be a directory dedicated to MINOS"), failure.getMessage());
            assertEquals(before, acl(profile), "a refused directory keeps its DACL");
        } finally {
            System.setProperty("user.home", previous);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPreExistingDirectoryOfAnotherOwnerIsRefusedAndItsAclIsLeftAlone() throws Exception {
        Path shared = Files.createDirectories(temp.resolve("shared"));
        List<AclEntry> before = acl(shared);
        // The owner of the Windows directory (TrustedInstaller) is never the user running the tests.
        UserPrincipal someoneElse = Files.getOwner(Path.of(System.getenv("SystemRoot")), LinkOption.NOFOLLOW_LINKS);
        assertNotEquals(Files.getOwner(shared, LinkOption.NOFOLLOW_LINKS), someoneElse);

        IOException failure = assertThrows(IOException.class,
                () -> MinosStrongProcessLauncher.requireOwnedBy(shared, someoneElse, "MINOS ownership home"));

        assertTrue(failure.getMessage().startsWith("MINOS ownership home already exists and belongs to another account"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(temp.toString()), failure.getMessage());
        assertEquals(before, acl(shared), "a refused directory keeps its DACL");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPreExistingDirectoryOfTheUserIsMadeOwnerOnly() throws Exception {
        Path home = Files.createDirectories(temp.resolve("existing-home"));

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(sleepingProcess(), home.toString());
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            UserPrincipal owner = Files.getOwner(home, LinkOption.NOFOLLOW_LINKS);
            assertTrue(acl(home).stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW
                    && entry.principal().equals(owner)), "the user's own directory is made owner-only");
            supervisor.stop(null);
        }
    }

    private ProcessBuilder sleepingProcess() {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 30"));
        builder.directory(temp.toFile());
        return builder;
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
    }
}
