package com.minos.git;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The remote cache holds full clones of possibly private repositories, so its tree must never be
 * readable by other local users -- including on a MINOS_HOME created before the policy existed.
 */
class RemoteCachePrivateStorageTest {

    @Test
    void materializerCacheTreeIsPrivateOnAFreshHome(@TempDir Path home) throws Exception {
        new JGitRemoteRepositoryMaterializer(home);

        for (String relative : new String[]{
                "remote-cache", "remote-cache/repositories", "remote-cache/locks", "remote-cache/leases"}) {
            Path directory = home.resolve(relative);
            assertNotEquals(PrivateLocalStorage.Privacy.ABSENT, PrivateLocalStorage.privacyOf(directory),
                    relative + " was not created");
            assertNotEquals(PrivateLocalStorage.Privacy.EXPOSED, PrivateLocalStorage.privacyOf(directory),
                    relative + " is readable beyond its owner");
            PrivateLocalStorage.verifyPrivateDirectory(directory);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void materializerHardensARemoteCacheLeftWorldReadableByAnEarlierInstallation(@TempDir Path home) throws Exception {
        // An installation created before the policy: every level 0755.
        Path repositories = Files.createDirectories(home.resolve("remote-cache/repositories"));
        Files.createDirectories(home.resolve("remote-cache/locks"));
        Files.createDirectories(home.resolve("remote-cache/leases"));
        for (String relative : new String[]{
                "remote-cache", "remote-cache/repositories", "remote-cache/locks", "remote-cache/leases"}) {
            Files.setPosixFilePermissions(home.resolve(relative), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertEquals(PrivateLocalStorage.Privacy.EXPOSED, PrivateLocalStorage.privacyOf(repositories));

        new JGitRemoteRepositoryMaterializer(home);

        for (String relative : new String[]{
                "remote-cache", "remote-cache/repositories", "remote-cache/locks", "remote-cache/leases"}) {
            assertEquals("rwx------", PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(home.resolve(relative), LinkOption.NOFOLLOW_LINKS)),
                    relative + " was not hardened");
        }
    }
}
