package com.minos.store;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Snapshot storage holds symbols, occurrences and relationships extracted from user code, so its
 * directories are covered by the same owner-only policy as the caches.
 */
class LocalStorePrivateStorageTest {

    @Test
    void snapshotStorageRootAndProjectDirectoriesArePrivate(@TempDir Path home) throws Exception {
        Path storageRoot = home.resolve("snapshots");
        SnapshotRepository repository = new SnapshotRepository(storageRoot);
        Path projectDirectory = repository.ensureProjectDirectory(UUID.randomUUID());

        for (Path directory : new Path[]{storageRoot, projectDirectory}) {
            assertNotEquals(PrivateLocalStorage.Privacy.EXPOSED, PrivateLocalStorage.privacyOf(directory),
                    directory + " is readable beyond its owner");
            PrivateLocalStorage.verifyPrivateDirectory(directory);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void snapshotStorageHardensAWorldReadableProjectDirectory(@TempDir Path home) throws Exception {
        Path storageRoot = Files.createDirectories(home.resolve("snapshots"));
        Files.setPosixFilePermissions(storageRoot, PosixFilePermissions.fromString("rwxr-xr-x"));
        UUID projectId = UUID.randomUUID();
        Path legacyProject = Files.createDirectories(storageRoot.resolve(projectId.toString()));
        Files.setPosixFilePermissions(legacyProject, PosixFilePermissions.fromString("rwxr-xr-x"));

        SnapshotRepository repository = new SnapshotRepository(storageRoot);
        Path projectDirectory = repository.ensureProjectDirectory(projectId);

        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(storageRoot, LinkOption.NOFOLLOW_LINKS)));
        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(projectDirectory, LinkOption.NOFOLLOW_LINKS)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void snapshotScratchFilesAreOwnerOnly(@TempDir Path home) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(home.resolve("snapshots"));
        UUID projectId = UUID.randomUUID();

        for (Path temporary : new Path[]{
                repository.createTemporarySnapshot(projectId),
                repository.createTemporaryPointer(projectId)}) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(temporary, LinkOption.NOFOLLOW_LINKS)),
                    temporary + " must not be readable beyond its owner");
        }
    }
}
