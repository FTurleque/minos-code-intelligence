package com.minos.integration.git;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteCloneBudgetTest {
    @TempDir
    Path root;

    @Test
    void anEntryThatVanishesDuringTheWalkIsSkipped() {
        Path lock = measuredRoot().resolve(".git").resolve("gc.log.lock");

        assertDoesNotThrow(() -> budget().tolerateVanishedEntry(lock,
                new NoSuchFileException("repository/.git/gc.log.lock")));
    }

    @Test
    void anyOtherFailureOfTheWalkStillFailsTheBudget() {
        AccessDeniedException denied = new AccessDeniedException("repository/.git/objects");
        Path objects = measuredRoot().resolve(".git").resolve("objects");

        IOException thrown = assertThrows(IOException.class, () -> budget().tolerateVanishedEntry(objects, denied));

        assertSame(denied, thrown);
    }

    @Test
    void aFileThatVanishesDuringTheWalkIsCountedAsAnEntryOnlyAndTheWalkGoesOn() throws IOException {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();

        FileVisitResult result = meter.visitFileFailed(measuredRoot().resolve(".git").resolve("gc.log.lock"),
                new NoSuchFileException("repository/.git/gc.log.lock"));

        assertEquals(FileVisitResult.CONTINUE, result);
        assertEquals(new RemoteCloneBudget.TreeMetrics(0L, 0L, 0L, 1L), meter.metrics());
    }

    @Test
    void aFileThatCannotBeReadFailsTheWalk() {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        AccessDeniedException denied = new AccessDeniedException("repository/.git/objects/pack");
        Path pack = measuredRoot().resolve(".git").resolve("objects").resolve("pack");

        assertSame(denied, assertThrows(IOException.class, () -> meter.visitFileFailed(pack, denied)));
    }

    @Test
    void aDirectoryThatVanishesBeforeItsListingEndsIsSkippedAndAnyOtherFailureFailsTheWalk() throws IOException {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        AccessDeniedException denied = new AccessDeniedException("repository/.git/refs");
        Path refs = measuredRoot().resolve(".git").resolve("refs");

        assertEquals(FileVisitResult.CONTINUE, meter.postVisitDirectory(refs, null));
        assertEquals(FileVisitResult.CONTINUE,
                meter.postVisitDirectory(refs, new NoSuchFileException("repository/.git/refs")));
        assertSame(denied, assertThrows(IOException.class, () -> meter.postVisitDirectory(refs, denied)));
    }

    @Test
    void aMeasuredRootThatCannotBeReadStillFailsWithItsOwnFailure() {
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        AccessDeniedException denied = new AccessDeniedException("repository");

        assertSame(denied, assertThrows(IOException.class, () -> meter.visitFileFailed(measuredRoot(), denied)));
    }

    private Path measuredRoot() {
        return root.resolve("repository");
    }

    private RemoteCloneBudget budget() {
        return new RemoteCloneBudget(measuredRoot(), RemoteRepositoryCachePolicy.DEFAULT);
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

    @Test
    void aMeasuredRootThatIsMissingWhenItIsWalkedFailsTheMeasure() {
        Path missingRoot = measuredRoot();
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();

        IOException thrown = assertThrows(IOException.class, () -> Files.walkFileTree(missingRoot, meter));

        assertFalse(thrown.getMessage().contains(missingRoot.toAbsolutePath().toString()), thrown.getMessage());
        assertInstanceOf(NoSuchFileException.class, thrown.getCause());
    }

    @Test
    void aMeasuredRootThatVanishesBeforeItsListingEndsFailsTheMeasure() {
        Path measuredRoot = measuredRoot();
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        NoSuchFileException vanished = new NoSuchFileException(measuredRoot.toAbsolutePath().toString());

        IOException thrown = assertThrows(IOException.class, () -> meter.postVisitDirectory(measuredRoot, vanished));

        assertFalse(thrown.getMessage().contains(measuredRoot.toAbsolutePath().toString()), thrown.getMessage());
        assertSame(vanished, thrown.getCause());
    }

    @Test
    void aDirectoryThatVanishesDuringARealWalkIsCountedAsAnEntryOnly() throws IOException {
        Path measuredRoot = Files.createDirectories(measuredRoot());
        Path first = Files.createDirectory(measuredRoot.resolve("a"));
        Path second = Files.createDirectory(measuredRoot.resolve("b"));
        RemoteCloneBudget.TreeMeter meter = budget().new TreeMeter();
        SiblingRemovingVisitor visitor = new SiblingRemovingVisitor(measuredRoot, first, second, meter);

        assertDoesNotThrow(() -> Files.walkFileTree(measuredRoot, visitor));

        assertTrue(visitor.removed != null && visitor.failedEntries.contains(visitor.removed),
                "the meter must receive the failure of the removed directory");
        assertEquals(3L, meter.metrics().traversalEntries());
        assertEquals(2L, meter.metrics().directories());
    }

    /** Delegates every event to the meter and removes the other sibling at the first visit of a sibling. */
    private static final class SiblingRemovingVisitor extends SimpleFileVisitor<Path> {
        private final Path measuredRoot;
        private final Path first;
        private final Path second;
        private final RemoteCloneBudget.TreeMeter meter;
        private final List<Path> failedEntries = new ArrayList<>();
        private Path removed;

        private SiblingRemovingVisitor(Path measuredRoot, Path first, Path second, RemoteCloneBudget.TreeMeter meter) {
            this.measuredRoot = measuredRoot;
            this.first = first;
            this.second = second;
            this.meter = meter;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            FileVisitResult result = meter.preVisitDirectory(directory, attributes);
            if (removed == null && !directory.equals(measuredRoot)) {
                removed = directory.equals(first) ? second : first;
                Files.delete(removed);
            }
            return result;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            return meter.visitFile(file, attributes);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            failedEntries.add(file);
            return meter.visitFileFailed(file, failure);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            return meter.postVisitDirectory(directory, failure);
        }
    }
}
