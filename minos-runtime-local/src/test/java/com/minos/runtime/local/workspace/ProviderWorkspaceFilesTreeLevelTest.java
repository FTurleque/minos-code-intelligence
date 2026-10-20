package com.minos.runtime.local.workspace;

import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The project copied for a provider takes the access of its tree: no per-file hardening (AUD-SEC-03). */
class ProviderWorkspaceFilesTreeLevelTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aCopiedFileIsOwnerOnlyAtCreationOnPosix(@TempDir Path home) throws Exception {
        Path source = Files.createDirectories(home.resolve("source/src"));
        Files.writeString(source.resolve("Main.java"), "class Main {}\n");
        Path target = home.resolve("copy");

        ProviderWorkspaceFiles.copyWorkspace(
                home.resolve("source"), target, new SourceBudgetPolicy(100, 1024 * 1024), "test workspace");

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target.resolve("src/Main.java")));
    }

    @Test
    void theCopyIsCompleteAndByteIdentical(@TempDir Path home) throws Exception {
        Path source = Files.createDirectories(home.resolve("source/a/b"));
        byte[] content = new byte[70_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
        Files.write(source.resolve("Big.bin"), content);
        Path target = home.resolve("copy");

        ProviderWorkspaceFiles.copyWorkspace(
                home.resolve("source"), target, new SourceBudgetPolicy(100, 1024 * 1024), "test workspace");

        assertArrayEquals(content, Files.readAllBytes(target.resolve("a/b/Big.bin")));
    }
}
