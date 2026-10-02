package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrivateLocalStorageWritesTest {

    @Test
    void writePrivateFileCreatesAnOwnerOnlyFileWithTheContent(@TempDir Path root) throws Exception {
        Path file = PrivateLocalStorage.writePrivateFile(
                root.resolve("a").resolve("pin"), "x=1".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("x=1".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file.getParent()));
    }

    @Test
    void writePrivateFileReplacesAndHardensAnExistingPermissiveFile(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("pin"), "old old old", StandardCharsets.UTF_8);

        PrivateLocalStorage.writePrivateFile(file, "new".getBytes(StandardCharsets.UTF_8));

        assertEquals("new", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    void writePrivateFileNeverWritesThroughALink(@TempDir Path root) throws Exception {
        Path outside = Files.writeString(root.resolve("outside.txt"), "untouched", StandardCharsets.UTF_8);
        Path link = root.resolve("pin");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
        }

        assertThrows(IOException.class,
                () -> PrivateLocalStorage.writePrivateFile(link, "overwritten".getBytes(StandardCharsets.UTF_8)));
        assertEquals("untouched", Files.readString(outside, StandardCharsets.UTF_8));
    }

    /**
     * On an ACL platform the owner entry of a private directory is inheritable, so whatever a process of
     * the owner creates in it later -- here a plain Files.createFile, in production the artifact a
     * sandboxed provider writes -- is owner-only too instead of taking the default ACL of its creator.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileCreatedLaterInAPrivateDirectoryInheritsOwnerOnlyAccess(@TempDir Path root) throws Exception {
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("run"));

        Path child = Files.createFile(directory.resolve("artifact.scip"));
        Path subdirectory = Files.createDirectory(directory.resolve("nested"));
        Path grandchild = Files.createFile(subdirectory.resolve("deeper.scip"));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(child));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(subdirectory));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(grandchild));
    }

    @Test
    void aFileOccupyingTheDirectoryNameIsRefusedAsAlreadyExistingWithoutNamingThePath(@TempDir Path root)
            throws Exception {
        Path blocker = Files.writeString(root.resolve("leases"), "not a directory", StandardCharsets.UTF_8);

        java.nio.file.FileAlreadyExistsException failure = assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> PrivateLocalStorage.ensurePrivateDirectory(blocker));

        assertEquals(false, String.valueOf(failure.getMessage()).contains(root.toString()));
        assertEquals("not a directory", Files.readString(blocker, StandardCharsets.UTF_8));
    }

    @Test
    void createPrivateTempDirectoryIsOwnerOnlyAndUnique(@TempDir Path root) throws Exception {
        Path first = PrivateLocalStorage.createPrivateTempDirectory(root.resolve("cache"), ".accept-");
        Path second = PrivateLocalStorage.createPrivateTempDirectory(root.resolve("cache"), ".accept-");

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(first));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(first.getParent()));
        assertNotEquals(first, second);
    }
}
