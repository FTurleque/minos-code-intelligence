package com.minos.io;

import org.junit.jupiter.api.Test;
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

    @Test
    void createPrivateTempDirectoryIsOwnerOnlyAndUnique(@TempDir Path root) throws Exception {
        Path first = PrivateLocalStorage.createPrivateTempDirectory(root.resolve("cache"), ".accept-");
        Path second = PrivateLocalStorage.createPrivateTempDirectory(root.resolve("cache"), ".accept-");

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(first));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(first.getParent()));
        assertNotEquals(first, second);
    }
}
