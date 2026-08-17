package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableFileSystemIdentityTest {

    @TempDir
    Path temporary;

    @Test
    void currentCiFilesystemProvidesStrongDirectoryIdentity() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));

        assertTrue(StableFileSystemIdentity.capture(project.toRealPath()).isPresent(),
                "supported CI filesystems must expose a strong filesystem-object identity");
    }

    @Test
    void replacementAtSameCanonicalPathChangesStrongIdentity() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        String before = StableFileSystemIdentity.capture(module.toRealPath()).orElseThrow();

        Files.move(module, project.resolve("module-original"));
        Files.createDirectory(module);

        String after = StableFileSystemIdentity.capture(module.toRealPath()).orElseThrow();
        assertNotEquals(before, after,
                "physical replacement at the same pathname must produce a different strong identity");
    }
}
