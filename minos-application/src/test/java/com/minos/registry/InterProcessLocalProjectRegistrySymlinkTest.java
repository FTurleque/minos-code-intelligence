package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InterProcessLocalProjectRegistrySymlinkTest {

    @Test
    void rejectsSymlinkedRegistryRoot(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path root = temp.resolve("registry");
        if (!createSymbolicLink(root, outside)) return;

        assertThrows(IOException.class, () -> new InterProcessLocalProjectRegistry(root));
    }

    @Test
    void rejectsSymlinkedRegistryLock(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("registry");
        InterProcessLocalProjectRegistry registry = new InterProcessLocalProjectRegistry(root);
        Path outside = Files.writeString(temp.resolve("outside.lock"), "outside");
        if (!createSymbolicLink(root.resolve(".registry.lock"), outside)) return;
        Path project = Files.createDirectories(temp.resolve("project"));

        assertThrows(IOException.class, () -> registry.registerProject(project, "fixture"));
    }

    @Test
    void rejectsSymlinkedProjectMetadataBeforeDelegateCanReadIt(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("registry");
        InterProcessLocalProjectRegistry registry = new InterProcessLocalProjectRegistry(root);
        Path project = Files.createDirectories(temp.resolve("project"));
        RegisteredProject registered = registry.registerProject(project, "fixture");
        Path metadata = root.resolve("projects").resolve(registered.id() + ".properties");
        Path outside = temp.resolve("outside.properties");
        Files.move(metadata, outside, StandardCopyOption.REPLACE_EXISTING);
        if (!createSymbolicLink(metadata, outside)) return;

        assertThrows(IOException.class, () -> registry.findProject(registered.id()));
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
