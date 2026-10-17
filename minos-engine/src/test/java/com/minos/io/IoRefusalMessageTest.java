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

    @Test
    void aDeletionDurabilitySyncFailureDoesNotNameTheAbsolutePath(@TempDir Path temp) throws Exception {
        Path target = Files.writeString(temp.resolve("record.properties"), "value");

        CommitUncertainException failure = assertThrows(CommitUncertainException.class,
                () -> DurableAtomicFile.deleteIfExists(target, "record store", parent -> {
                    throw new IOException("synthetic directory fsync failure");
                }));

        assertNoAbsolutePath(failure, temp);
        assertTrue(failure.getMessage().contains("record.properties"), failure.getMessage());
    }

    @Test
    void aMoveDurabilityAcknowledgementFailureDoesNotNameTheAbsolutePath(@TempDir Path temp) throws Exception {
        Path source = Files.writeString(temp.resolve("next.tmp"), "new-value");
        Path target = temp.resolve("record.properties");

        CommitUncertainException failure = assertThrows(CommitUncertainException.class,
                () -> DurableAtomicFile.move(source, target, true, "record store", parent -> {
                    throw new IOException("synthetic directory fsync failure");
                }));

        assertNoAbsolutePath(failure, temp);
        assertTrue(failure.getMessage().contains("record.properties"), failure.getMessage());
    }

    @Test
    void anUnsupportedAtomicMoveDoesNotNameTheAbsolutePath(@TempDir Path temp) throws Exception {
        Path source = Files.writeString(temp.resolve("next.tmp"), "new-value");
        Path target = temp.resolve("record.properties");
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform((from, to, options) -> {
            throw new java.nio.file.AtomicMoveNotSupportedException(from.toString(), to.toString(), "synthetic");
        }, false, pause -> { });

        IOException failure = assertThrows(IOException.class, () -> DurableAtomicFile.move(
                source, target, true, "replacement", directory -> { }, platform));

        assertNoAbsolutePath(failure, temp);
        assertTrue(failure.getMessage().contains("required atomic replacement"), failure.getMessage());
    }

    @Test
    void anUnsupportedExclusivePublicationDoesNotNameTheAbsolutePath(@TempDir Path temp) throws Exception {
        Path source = Files.writeString(temp.resolve("next.tmp"), "new-value");
        Path target = temp.resolve("record.properties");
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform(
                Files::move, (link, existing) -> { throw new UnsupportedOperationException("no hard links"); },
                false, pause -> { });

        IOException failure = assertThrows(IOException.class, () -> DurableAtomicFile.move(
                source, target, false, "publication", directory -> { }, platform));

        assertNoAbsolutePath(failure, temp);
        assertTrue(failure.getMessage().contains("required exclusive publication"), failure.getMessage());
    }

    private static void assertNoAbsolutePath(Throwable failure, Path testDirectory) {
        String message = failure.getMessage();
        assertFalse(message.contains(testDirectory.toString()), message);
        assertFalse(message.contains(testDirectory.toAbsolutePath().normalize().toString()), message);
        assertFalse(message.contains(testDirectory.getFileName().toString()), message);
    }
}
