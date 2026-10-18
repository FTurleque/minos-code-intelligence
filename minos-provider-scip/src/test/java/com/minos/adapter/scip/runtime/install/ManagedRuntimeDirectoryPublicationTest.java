package com.minos.adapter.scip.runtime.install;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal of a polyglot command and the publication of the polyglot and Python runtime directories
 * (AUD-SEC-03).
 */
class ManagedRuntimeDirectoryPublicationTest {

    private static Path tree(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("marker.txt"), content);
        return directory;
    }

    // ------------------------------------------------------------------ command journal

    @Test
    void theJournalIsAPrivateFileInADedicatedFolderOfTheWorkingDirectory(@TempDir Path home) throws Exception {
        Path log = ManagedPolyglotScipRuntimeManager.newCommandLog(home);

        assertEquals(home.toRealPath().resolve(ManagedPolyglotScipRuntimeManager.COMMAND_LOG_DIRECTORY),
                log.getParent().toRealPath());
        assertTrue(Files.isRegularFile(log));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(log));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(log.getParent()));
    }

    @Test
    void aWorkingDirectoryWhichIsALinkIsRefused(@TempDir Path temporary) throws Exception {
        Path real = Files.createDirectories(temporary.resolve("real"));
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, real.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.abort("symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }

        assertThrows(IOException.class, () -> ManagedPolyglotScipRuntimeManager.newCommandLog(link));

        assertFalse(Files.exists(real.resolve(ManagedPolyglotScipRuntimeManager.COMMAND_LOG_DIRECTORY)));
    }

    @Test
    void aMissingWorkingDirectoryIsNeitherCreatedNorHardenedByAStatusRead(@TempDir Path temporary) {
        Path missing = temporary.resolve("no-home-yet");

        IOException refusal = assertThrows(IOException.class, () -> ManagedPolyglotScipRuntimeManager.newCommandLog(missing));

        assertFalse(refusal.getMessage().contains(temporary.toString()), "no absolute path in the message");
        assertFalse(Files.exists(missing));
    }

    // ------------------------------------------------------------------ polyglot directory

    @Test
    void thePolyglotDirectoryReplacesWhatWasInstalled(@TempDir Path temporary) throws Exception {
        Path destination = tree(temporary.resolve("tools/go/1.0"), "old");
        Path source = tree(temporary.resolve("tools/go/1.0.partial"), "new");

        ManagedPolyglotScipRuntimeManager.replaceDirectory(source, destination);

        assertEquals("new", Files.readString(destination.resolve("marker.txt")));
        assertFalse(Files.exists(source));
    }

    @Test
    void thePolyglotDirectoryFailsWhenItsSourceIsAbsent(@TempDir Path temporary) {
        assertThrows(IOException.class, () -> ManagedPolyglotScipRuntimeManager.replaceDirectory(
                temporary.resolve("absent.partial"), temporary.resolve("tools/go/1.0")));
    }

    // ------------------------------------------------------------------ Python directory

    @Test
    void thePythonDirectoryIsPublishedUnderAPrivateParent(@TempDir Path temporary) throws Exception {
        Path source = tree(temporary.resolve("tools/python/1.0.partial"), "python");
        Path target = temporary.resolve("tools/python-final/1.0");

        ManagedScipPythonRuntimeManager.moveDirectory(source, target);

        assertEquals("python", Files.readString(target.resolve("marker.txt")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(target.getParent()));
    }

    @Test
    void thePythonDirectoryIsNeverPublishedOverAnExistingOne(@TempDir Path temporary) throws Exception {
        Path source = tree(temporary.resolve("tools/python/1.0.partial"), "new");
        Path target = tree(temporary.resolve("tools/python/1.0"), "installed");

        assertThrows(FileAlreadyExistsException.class, () -> ManagedScipPythonRuntimeManager.moveDirectory(source, target));

        assertEquals("installed", Files.readString(target.resolve("marker.txt")));
        assertEquals("new", Files.readString(source.resolve("marker.txt")));
    }
}
