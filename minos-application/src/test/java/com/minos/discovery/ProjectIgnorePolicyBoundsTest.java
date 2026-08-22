package com.minos.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectIgnorePolicyBoundsTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsOversizedRootIgnoreFileWhileReading() throws Exception {
        Files.writeString(temporary.resolve(".gitignore"), "x".repeat(1024 * 1024 + 1));
        assertThrows(IOException.class, () -> ProjectIgnorePolicy.load(temporary));
    }
}
