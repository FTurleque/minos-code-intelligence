package com.minos.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableAtomicFileTest {

    @AfterEach
    void resetCapabilityProbe() {
        PrivateLocalStorage.resetCapabilityProbeForTesting();
    }

    @Test
    void ensureDirectoryFailsClosedWhenTheFilesystemCannotEnforcePrivacy(@TempDir Path root) {
        // DurableAtomicFile delegates directory privacy entirely to PrivateLocalStorage: a
        // control-plane directory it creates must never be usable when ownership cannot be
        // guaranteed for it, on the same terms as every other private-storage consumer.
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());
        Path directory = root.resolve("hosted-control-plane");

        assertThrows(IOException.class, () -> DurableAtomicFile.ensureDirectory(directory, "control-plane"));
        assertFalse(Files.isDirectory(directory), "a directory that could not be made private must not remain");
    }

    @Test
    void directorySyncFailureAfterAtomicMoveIsExplicitlyCommitUncertain(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");

        CommitUncertainException failure = assertThrows(
                CommitUncertainException.class,
                () -> DurableAtomicFile.move(
                        source,
                        target,
                        true,
                        "test replacement",
                        directory -> { throw new IOException("synthetic directory fsync failure"); }));

        assertTrue(failure.getMessage().contains("committed"));
        assertEquals("new-value", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void aReplacementHeldOpenByAReaderIsRetriedOnWindowsUntilItSucceeds(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> pauses = new ArrayList<>();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform(
                (from, to, options) -> {
                    if (attempts.incrementAndGet() <= 2) throw new AccessDeniedException(to.toString());
                    Files.move(from, to, options);
                },
                true,
                pauses::add);

        DurableAtomicFile.move(source, target, true, "test replacement", directory -> { }, platform);

        assertEquals("new-value", Files.readString(target));
        assertEquals(3, attempts.get());
        assertEquals(List.of(1, 2), pauses, "one growing pause after each refused attempt");
    }

    @Test
    void aSharingViolationIsRetriedLikeAnAccessDeniedError(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");
        AtomicInteger attempts = new AtomicInteger();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform(
                (from, to, options) -> {
                    if (attempts.incrementAndGet() == 1) throw new FileSystemException(to.toString());
                    Files.move(from, to, options);
                },
                true,
                pause -> { });

        DurableAtomicFile.move(source, target, true, "test replacement", directory -> { }, platform);

        assertEquals("new-value", Files.readString(target));
        assertEquals(2, attempts.get());
    }

    @Test
    void theRetryIsBoundedAndTheLastFailureIsReported(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();
        DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform(
                (from, to, options) -> {
                    attempts.incrementAndGet();
                    throw new AccessDeniedException(to.toString());
                },
                true,
                pause -> pauses.incrementAndGet());

        assertThrows(AccessDeniedException.class,
                () -> DurableAtomicFile.move(source, target, true, "test replacement", directory -> { }, platform));

        assertEquals(DurableAtomicFile.REPLACE_ATTEMPTS_ON_WINDOWS, attempts.get());
        assertEquals(DurableAtomicFile.REPLACE_ATTEMPTS_ON_WINDOWS - 1, pauses.get());
        assertEquals("old-value", Files.readString(target), "a replacement that never succeeded changes nothing");
    }

    @Test
    void aReplacementIsNeverRetriedOutsideWindowsNorForAFinalFailureNorForAPublication(@TempDir Path root)
            throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();
        DurableAtomicFile.FileMover denied = (from, to, options) -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(to.toString());
        };
        DurableAtomicFile.FileMover missing = (from, to, options) -> {
            attempts.incrementAndGet();
            throw new NoSuchFileException(from.toString());
        };

        assertThrows(AccessDeniedException.class, () -> DurableAtomicFile.move(
                source, target, true, "linux", directory -> { },
                new DurableAtomicFile.Platform(denied, false, pause -> pauses.incrementAndGet())));
        assertThrows(NoSuchFileException.class, () -> DurableAtomicFile.move(
                source, target, true, "missing source", directory -> { },
                new DurableAtomicFile.Platform(missing, true, pause -> pauses.incrementAndGet())));
        assertThrows(AccessDeniedException.class, () -> DurableAtomicFile.move(
                source, target, false, "publication", directory -> { },
                new DurableAtomicFile.Platform(denied, true, pause -> pauses.incrementAndGet())));

        assertEquals(3, attempts.get(), "each of the three refusals was final at its first attempt");
        assertEquals(0, pauses.get());
    }
}
