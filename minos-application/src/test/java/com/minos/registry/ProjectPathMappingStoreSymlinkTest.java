package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectPathMappingStoreSymlinkTest {

    @Test
    void rejectsSymlinkedRuntimeDirectoryBeforeReadingMapping(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        if (!createSymbolicLink(home.resolve("runtime"), outside)) return;

        assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());
    }

    @Test
    void rejectsSymlinkedMappingFile(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path hostRoot = Files.createDirectories(temp.resolve("host"));
        Path containerRoot = Files.createDirectories(temp.resolve("container"));
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(new ProjectPathMapping(hostRoot.toString(), containerRoot.toString()));
        Path mapping = store.file();
        Path outside = temp.resolve("outside.properties");
        Files.move(mapping, outside, StandardCopyOption.REPLACE_EXISTING);
        if (!createSymbolicLink(mapping, outside)) return;

        assertThrows(IOException.class, store::loadOptional);
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
