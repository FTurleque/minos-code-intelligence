package com.minos.storage.local;

import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.registry.ProjectPathMappingStore;
import com.minos.storage.local.store.FileSemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The local stores refuse a file of the wrong type or with invalid content by its file name, never by the absolute
 * path under MINOS_HOME, which carries the user name and the layout of the home (AUD-SEC-04).
 */
class StorageRefusalMessagePathTest {

    @TempDir
    Path temp;

    @Test
    void aSemanticIndexWithAnInvalidMagicIsRefusedByItsFileName() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        Path project = Files.createDirectories(root.resolve("project-1"));
        Files.write(project.resolve("index-v2.bin"), new byte[64]);

        for (Executable read : new Executable[] {
                () -> store.load("project-1"), () -> store.metadata("project-1"), () -> store.formatVersion("project-1")}) {
            IOException refusal = assertThrows(IOException.class, read::run);
            assertEquals("invalid semantic index magic: index-v2.bin", refusal.getMessage());
            assertNoAbsolutePath(refusal);
        }
    }

    @Test
    void aSemanticIndexEntryThatIsADirectoryIsRefusedByItsName() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        Files.createDirectories(root.resolve("project-1").resolve("index-v2.bin"));

        IOException refusal = assertThrows(IOException.class, () -> store.load("project-1"));

        assertEquals("semantic index entry must be a regular file: index-v2.bin", refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aProjectRootThatIsAFileIsRefusedByItsName() throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        Path file = Files.writeString(temp.resolve("not-a-directory.txt"), "x");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> registry.registerProject(file, "File"));

        assertEquals("rootPath must be an existing directory: not-a-directory.txt", refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aPathMappingOfTheWrongTypeIsRefusedByItsName() throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home.resolve(ProjectPathMappingStore.RUNTIME_DIRECTORY)
                .resolve(ProjectPathMappingStore.FILE_NAME));

        IOException refusal = assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());

        assertEquals("project path mapping is not a regular non-symlink file: " + ProjectPathMappingStore.FILE_NAME,
                refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aPathMappingDirectoryThatIsAFileIsRefusedByItsName() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.writeString(home.resolve(ProjectPathMappingStore.RUNTIME_DIRECTORY), "x");

        IOException refusal = assertThrows(IOException.class, () -> new ProjectPathMappingStore(home).loadOptional());

        assertEquals("project path mapping directory must be a non-symlink directory: "
                + ProjectPathMappingStore.RUNTIME_DIRECTORY, refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    private void assertNoAbsolutePath(Exception refusal) {
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Exception;
    }
}
