package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedPropertiesSymlinkTest {

    @Test
    void loadRejectsSymlinkedPropertiesFile(@TempDir Path temp) throws Exception {
        Path target = Files.writeString(temp.resolve("outside.properties"), "key=value\n");
        Path link = temp.resolve("metadata.properties");
        if (!createSymbolicLink(link, target)) return;

        assertThrows(IOException.class, () -> BoundedProperties.load(
                link, 1024, 4, 32, 128, "symlink fixture"));
    }

    @Test
    void readUtf8RejectsSymlinkedFile(@TempDir Path temp) throws Exception {
        Path target = Files.writeString(temp.resolve("outside.txt"), "value");
        Path link = temp.resolve("metadata.txt");
        if (!createSymbolicLink(link, target)) return;

        assertThrows(IOException.class, () -> BoundedProperties.readUtf8(link, 1024, "symlink fixture"));
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
