package com.minos.integration.git;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteCloneBudgetTest {
    @TempDir
    Path root;

    @Test
    void anEntryThatVanishesDuringTheWalkIsSkipped() {
        assertDoesNotThrow(() -> RemoteCloneBudget.tolerateVanishedEntry(
                new NoSuchFileException("repository/.git/gc.log.lock")));
    }

    @Test
    void anyOtherFailureOfTheWalkStillFailsTheBudget() {
        AccessDeniedException denied = new AccessDeniedException("repository/.git/objects");

        IOException thrown = assertThrows(IOException.class, () -> RemoteCloneBudget.tolerateVanishedEntry(denied));

        assertSame(denied, thrown);
    }

    @Test
    void aFileThatVanishesDuringTheWalkIsCountedAsAnEntryOnlyAndTheWalkGoesOn() throws IOException {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();

        FileVisitResult result = meter.visitFileFailed(root.resolve("gc.log.lock"),
                new NoSuchFileException("repository/.git/gc.log.lock"));

        assertEquals(FileVisitResult.CONTINUE, result);
        assertEquals(new RemoteCloneBudget.TreeMetrics(0L, 0L, 0L, 1L), meter.metrics());
    }

    @Test
    void aFileThatCannotBeReadFailsTheWalk() {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        AccessDeniedException denied = new AccessDeniedException("repository/.git/objects/pack");

        assertSame(denied, assertThrows(IOException.class, () -> meter.visitFileFailed(root, denied)));
    }

    @Test
    void aDirectoryThatVanishesBeforeItsListingEndsIsSkippedAndAnyOtherFailureFailsTheWalk() throws IOException {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        AccessDeniedException denied = new AccessDeniedException("repository/.git/refs");

        assertEquals(FileVisitResult.CONTINUE, meter.postVisitDirectory(root, null));
        assertEquals(FileVisitResult.CONTINUE,
                meter.postVisitDirectory(root, new NoSuchFileException("repository/.git/refs")));
        assertSame(denied, assertThrows(IOException.class, () -> meter.postVisitDirectory(root, denied)));
    }

    private RemoteCloneBudget budget() {
        return new RemoteCloneBudget(root.resolve("repository"), RemoteRepositoryCachePolicy.DEFAULT);
    }

    @Test
    void aStableTreeIsMeasured() throws IOException {
        Path destination = Files.createDirectories(root.resolve("repository").resolve(".git"));
        Files.writeString(destination.resolve("HEAD"), "ref: refs/heads/main\n");

        RemoteCloneBudget.TreeMetrics metrics =
                new RemoteCloneBudget(root.resolve("repository"), RemoteRepositoryCachePolicy.DEFAULT).checkpoint();

        assertEquals(1L, metrics.files());
        assertEquals(2L, metrics.directories());
        assertEquals(21L, metrics.bytes());
    }
}
