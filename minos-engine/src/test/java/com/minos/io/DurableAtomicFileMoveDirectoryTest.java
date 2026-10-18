package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link DurableAtomicFile#moveDirectory}: the exclusive, atomic rename of a directory entry (AUD-SEC-03). A
 * publication by hard link does not exist for a directory, so the move is a rename that refuses an existing target,
 * never follows the entry it renames, and never falls back to a copy.
 */
class DurableAtomicFileMoveDirectoryTest {

    private static final DurableAtomicFile.DirectorySync NO_SYNC = directory -> { };

    private static Path directoryWithAFile(Path root, String name) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        Files.writeString(directory.resolve("kept.txt"), "kept", StandardCharsets.UTF_8);
        return directory;
    }

    @Test
    void aDirectoryIsMovedToAFreeNameWithItsContent(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path target = root.resolve("published");

        DurableAtomicFile.moveDirectory(source, target, "test directory", NO_SYNC, DurableAtomicFile.Platform.SYSTEM);

        assertFalse(Files.exists(source));
        assertEquals("kept", Files.readString(target.resolve("kept.txt")));
    }

    @Test
    void thePublicOverloadMovesWithTheRealPlatform(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path target = root.resolve("published");

        DurableAtomicFile.moveDirectory(source, target, "test directory");

        assertEquals("kept", Files.readString(target.resolve("kept.txt")));
    }

    @Test
    void anExistingTargetIsRefusedWhateverItIsAndTheSourceIsLeftIntact(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path emptyDirectory = Files.createDirectory(root.resolve("empty-target"));
        Path fullDirectory = directoryWithAFile(root, "full-target");
        Path file = Files.writeString(root.resolve("file-target"), "file", StandardCharsets.UTF_8);

        // rename(2) would replace the empty directory: the move refuses it before renaming anything.
        for (Path target : List.of(emptyDirectory, fullDirectory, file)) {
            FileAlreadyExistsException refusal = assertThrows(FileAlreadyExistsException.class, () ->
                    DurableAtomicFile.moveDirectory(source, target, "test directory", NO_SYNC,
                            DurableAtomicFile.Platform.SYSTEM));
            assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()),
                    "the refusal must not carry the absolute path: " + refusal.getMessage());
        }

        assertEquals("kept", Files.readString(source.resolve("kept.txt")));
        assertTrue(Files.isDirectory(emptyDirectory));
        assertEquals("file", Files.readString(file));
    }

    @Test
    void anAbsentSourceIsReportedAsAbsent(@TempDir Path root) {
        assertThrows(NoSuchFileException.class, () -> DurableAtomicFile.moveDirectory(
                root.resolve("absent"), root.resolve("target"), "test directory", NO_SYNC,
                DurableAtomicFile.Platform.SYSTEM));
    }

    @Test
    void aLinkWhichReplacedTheSourceIsRenamedItselfAndNeverFollowed(@TempDir Path root) throws Exception {
        Path outside = directoryWithAFile(root, "outside");
        Path link = root.resolve("quarantined");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException _) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
        }
        Path target = root.resolve("moved-link");

        DurableAtomicFile.moveDirectory(link, target, "test directory", NO_SYNC, DurableAtomicFile.Platform.SYSTEM);

        assertTrue(Files.isSymbolicLink(target), "the entry itself moved, it was not followed");
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertEquals("kept", Files.readString(outside.resolve("kept.txt")), "the directory it pointed to is untouched");
    }

    @Test
    void aFileWhichReplacedTheSourceIsRenamedAsAnEntry(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("replaced"), "now a file", StandardCharsets.UTF_8);
        Path target = root.resolve("moved-file");

        DurableAtomicFile.moveDirectory(source, target, "test directory", NO_SYNC, DurableAtomicFile.Platform.SYSTEM);

        assertEquals("now a file", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void aFilesystemWithoutAtomicRenameFailsClosedAndNothingIsCopied(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path target = root.resolve("published");
        AtomicInteger attempts = new AtomicInteger();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform((from, to, options) -> {
            attempts.incrementAndGet();
            throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "no atomic rename");
        }, false, pause -> { });

        IOException failure = assertThrows(IOException.class, () -> DurableAtomicFile.moveDirectory(
                source, target, "test directory", NO_SYNC, platform));

        assertInstanceOf(AtomicMoveNotSupportedException.class, failure.getCause());
        assertFalse(failure.getMessage().contains(root.toString()),
                "the message must not carry the absolute path: " + failure.getMessage());
        assertEquals(1, attempts.get(), "an unsupported atomic move is final, never retried");
        assertFalse(Files.exists(target), "no copy is made");
        assertEquals("kept", Files.readString(source.resolve("kept.txt")));
    }

    @Test
    void theMoveAsksForAnAtomicRenameAndNeverForAReplacement(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        List<CopyOption[]> requested = new ArrayList<>();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform((from, to, options) -> {
            requested.add(options);
            Files.move(from, to, options);
        }, false, pause -> { });

        DurableAtomicFile.moveDirectory(source, root.resolve("published"), "test directory", NO_SYNC, platform);

        assertEquals(1, requested.size());
        assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE), List.of(requested.getFirst()));
    }

    @Test
    void aDirectoryHeldOpenByAReaderIsRetriedOnWindowsUntilItSucceeds(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path target = root.resolve("published");
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> pauses = new ArrayList<>();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform((from, to, options) -> {
            if (attempts.incrementAndGet() <= 2) throw new AccessDeniedException(from.toString());
            Files.move(from, to, options);
        }, true, pauses::add);

        DurableAtomicFile.moveDirectory(source, target, "test directory", NO_SYNC, platform);

        assertEquals(3, attempts.get());
        assertEquals(List.of(1, 2), pauses);
        assertEquals("kept", Files.readString(target.resolve("kept.txt")));
    }

    @Test
    void theRetryIsBoundedAndTheDirectoryStaysWhereItWas(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path target = root.resolve("published");
        AtomicInteger attempts = new AtomicInteger();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform((from, to, options) -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(from.toString());
        }, true, pause -> { });

        assertThrows(AccessDeniedException.class, () -> DurableAtomicFile.moveDirectory(
                source, target, "test directory", NO_SYNC, platform));

        assertEquals(DurableAtomicFile.REPLACE_ATTEMPTS_ON_WINDOWS, attempts.get());
        assertFalse(Files.exists(target));
        assertEquals("kept", Files.readString(source.resolve("kept.txt")));
    }

    @Test
    void aRefusalOutsideWindowsIsNeverRetried(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        AtomicInteger attempts = new AtomicInteger();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform((from, to, options) -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(from.toString());
        }, false, pause -> { });

        assertThrows(AccessDeniedException.class, () -> DurableAtomicFile.moveDirectory(
                source, root.resolve("published"), "test directory", NO_SYNC, platform));

        assertEquals(1, attempts.get());
    }

    @Test
    void aDirectorySyncFailureAfterTheRenameIsCommitUncertainAndKeepsTheTarget(@TempDir Path root) throws Exception {
        Path source = directoryWithAFile(root, "preparing");
        Path target = root.resolve("published");

        CommitUncertainException failure = assertThrows(
                CommitUncertainException.class, () -> DurableAtomicFile.moveDirectory(
                        source, target, "test directory", directory -> {
                            throw new IOException("sync failed");
                        }, DurableAtomicFile.Platform.SYSTEM));

        assertFalse(failure.getMessage().contains(root.toString()));
        assertEquals("kept", Files.readString(target.resolve("kept.txt")));
    }
}
