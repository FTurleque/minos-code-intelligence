package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Une primitive d'E/S privee qui refuse une cible la nomme par son etiquette ou son nom de fichier,
 * jamais par son chemin absolu : le message peut atteindre une sortie publique.
 */
class IoRefusalMessageTest {

    @Test
    void propertiesLoadOnADirectoryDoesNotNameTheAbsolutePath(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("minos.properties"));

        IOException refused = assertThrows(IOException.class, () -> BoundedProperties.load(
                directory, 1024, 4, 32, 128, "MINOS runtime configuration"));

        assertNoAbsolutePath(refused, temp);
        assertTrue(refused.getMessage().contains("MINOS runtime configuration"), refused.getMessage());
        assertTrue(refused.getMessage().contains("regular non-symlink file"), refused.getMessage());
    }

    @Test
    void utf8ReadOnADirectoryDoesNotNameTheAbsolutePath(@TempDir Path temp) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("secret.txt"));

        IOException refused = assertThrows(IOException.class,
                () -> BoundedProperties.readUtf8(directory, 1024, "MINOS secret file"));

        assertNoAbsolutePath(refused, temp);
    }

    @Test
    void aParentDurabilitySyncFailureDoesNotNameTheAbsolutePath(@TempDir Path temp) {
        Path directory = temp.resolve("state");

        CommitUncertainException failure = assertThrows(CommitUncertainException.class,
                () -> DurableAtomicFile.ensureDirectory(directory, "state store", parent -> {
                    throw new IOException("synthetic directory fsync failure");
                }));

        assertNoAbsolutePath(failure, temp);
        assertTrue(failure.getMessage().contains("state store"), failure.getMessage());
        assertTrue(failure.getMessage().contains("parent durability sync failed"), failure.getMessage());
    }

    @Test
    void anUnsupportedDirectorySyncDoesNotNameTheAbsolutePath(@TempDir Path temp) {
        Path directory = temp.resolve("data");

        IOException failure = DurableAtomicFile.unsupportedDirectorySync(
                directory, new UnsupportedOperationException("synthetic"));

        assertNoAbsolutePath(failure, temp);
        assertTrue(failure.getMessage().contains("directory durability sync"), failure.getMessage());
        assertEquals("synthetic", failure.getCause().getMessage());
    }

    private static void assertNoAbsolutePath(Throwable failure, Path temp) {
        String message = failure.getMessage();
        assertFalse(message.contains(temp.toString()), message);
        assertFalse(message.contains(temp.toAbsolutePath().normalize().toString()), message);
        assertFalse(message.contains(temp.getFileName().toString()), message);
    }
}
