package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTreeOperationsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesNestedTreesWithoutFollowingDirectorySymlinks() throws IOException {
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path evidence = Files.writeString(outside.resolve("evidence.txt"), "preserved");
        Path target = Files.createDirectories(temporaryDirectory.resolve("target/nested"));
        Files.writeString(target.resolve("inside.txt"), "deleted");
        Path link = temporaryDirectory.resolve("target/outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            link = null;
        }

        FileTreeOperations.deleteRecursively(temporaryDirectory.resolve("target"));

        assertFalse(Files.exists(temporaryDirectory.resolve("target")));
        assertTrue(Files.isRegularFile(evidence));
        if (link != null) assertFalse(Files.exists(link));
    }
}
