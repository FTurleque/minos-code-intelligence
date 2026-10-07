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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        // A publication no longer goes through the mover (a move may overwrite): its refusal comes from the linker.
        DurableAtomicFile.FileLinker deniedLink = (link, existing) -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(link.toString());
        };
        assertThrows(AccessDeniedException.class, () -> DurableAtomicFile.move(
                source, target, false, "publication", directory -> { },
                new DurableAtomicFile.Platform(denied, deniedLink, true, pause -> pauses.incrementAndGet())));

        assertEquals(3, attempts.get(), "each of the three refusals was final at its first attempt");
        assertEquals(0, pauses.get());
    }

    // MINOS-AUD-B09 : la Javadoc de publish promet qu'une cible existante n'est jamais remplacee ; ATOMIC_MOVE la remplacait,
    // sous Windows comme sous Linux (rename(2)). Tests sur un @TempDir reel, identiques sur les deux plateformes.

    @Test
    void publishingOverAnExistingTargetFailsAndLeavesItUnchanged(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("record.properties"), "original-value");

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> DurableAtomicFile.publish(source, target, "immutable record"));

        assertEquals("original-value", Files.readString(target), "an existing target is never modified");
        assertTrue(Files.exists(source), "a refused publication keeps its source for the caller to clean up");
    }

    @Test
    void publishingToAnAbsentTargetCommitsAndRemovesTheSource(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = root.resolve("record.properties");

        DurableAtomicFile.publish(source, target, "immutable record");

        assertEquals("new-value", Files.readString(target));
        assertFalse(Files.exists(source), "the source no longer exists once published");
    }

    @Test
    void twoConcurrentPublicationsOfTheSameTargetCommitExactlyOne(@TempDir Path root) throws Exception {
        Path target = root.resolve("record.properties");
        int contenders = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> results = new ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(contenders);
        try {
            for (int index = 0; index < contenders; index++) {
                Path source = Files.writeString(root.resolve("next-" + index + ".tmp"), "value-" + index);
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        DurableAtomicFile.publish(source, target, "immutable record");
                        return "published";
                    } catch (java.nio.file.FileAlreadyExistsException exists) {
                        return "exists";
                    }
                }));
            }
            start.countDown();
            int published = 0;
            int refused = 0;
            for (java.util.concurrent.Future<String> result : results) {
                String outcome = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if ("published".equals(outcome)) published++;
                else if ("exists".equals(outcome)) refused++;
            }
            assertEquals(1, published, "exactly one publication commits");
            assertEquals(contenders - 1, refused, "every other publication fails with 'already exists'");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(Files.readString(target).startsWith("value-"));
    }

    @Test
    void publishFailsClosedWhenTheFilesystemCannotLinkAndNeverOverwrites(@TempDir Path root) throws Exception {
        Path target = Files.writeString(root.resolve("record.properties"), "original-value");
        Path absent = root.resolve("absent.properties");
        AtomicInteger moves = new AtomicInteger();
        DurableAtomicFile.FileMover mover = (from, to, options) -> {
            moves.incrementAndGet();
            Files.move(from, to, options);
        };
        for (DurableAtomicFile.FileLinker linker : List.<DurableAtomicFile.FileLinker>of(
                (link, existing) -> { throw new UnsupportedOperationException("no hard links"); },
                (link, existing) -> { throw new FileSystemException(link.toString(), null, "Incorrect function"); })) {
            Path source = Files.writeString(root.resolve("next-" + System.nanoTime() + ".tmp"), "new-value");
            DurableAtomicFile.Platform platform = new DurableAtomicFile.Platform(mover, linker, false, pause -> { });

            IOException refusal = assertThrows(IOException.class, () -> DurableAtomicFile.move(
                    source, target, false, "publication", directory -> { }, platform));
            IOException refusalOnAbsentTarget = assertThrows(IOException.class, () -> DurableAtomicFile.move(
                    source, absent, false, "publication", directory -> { }, platform));

            assertTrue(refusal.getMessage().contains("does not support required exclusive publication"),
                    refusal.getMessage());
            assertTrue(refusalOnAbsentTarget.getMessage().contains("exclusive publication"));
            assertEquals("original-value", Files.readString(target), "a refused publication never overwrites");
            assertFalse(Files.exists(absent), "no fallback move may create the target");
            assertTrue(Files.exists(source));
        }
        assertEquals(0, moves.get(), "a publication never falls back to a move that could overwrite");
    }

    @Test
    void aMissingSourceAndAPermissionFailureStaySpecificForAPublication(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = root.resolve("record.properties");

        assertThrows(NoSuchFileException.class, () -> DurableAtomicFile.move(
                source, target, false, "publication", directory -> { },
                new DurableAtomicFile.Platform(Files::move, (link, existing) -> {
                    throw new NoSuchFileException(existing.toString());
                }, false, pause -> { })));
        assertThrows(AccessDeniedException.class, () -> DurableAtomicFile.move(
                source, target, false, "publication", directory -> { },
                new DurableAtomicFile.Platform(Files::move, (link, existing) -> {
                    throw new AccessDeniedException(link.toString());
                }, false, pause -> { })));
    }

    @Test
    void aDirectorySyncFailureAfterAPublicationIsCommitUncertainAndKeepsTheTarget(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = root.resolve("record.properties");

        assertThrows(CommitUncertainException.class, () -> DurableAtomicFile.move(
                source, target, false, "publication",
                directory -> { throw new IOException("synthetic directory fsync failure"); }));

        assertEquals("new-value", Files.readString(target), "the publication is committed even if its durability is not acknowledged");
        assertFalse(Files.exists(source));
    }

    @Test
    void replaceStillReplacesAnExistingTarget(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");

        DurableAtomicFile.replace(source, target, "control-plane file");

        assertEquals("new-value", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void anInterruptionDuringTheReplacementPauseKeepsItsCauseAndReplaysTheFlag() {
        // R6: the pause restored the flag but threw an IOException without cause, so the orchestration,
        // which looks for the interruption in the chain of causes, reported an ordinary failure.
        DurableAtomicFile.ReplacePause pause = DurableAtomicFile.Platform.SYSTEM.pause();
        Thread.currentThread().interrupt();
        IOException failure;
        boolean flagReplayed;
        try {
            failure = assertThrows(IOException.class, () -> pause.pause(1));
        } finally {
            flagReplayed = Thread.interrupted();
        }

        assertTrue(flagReplayed, "the interrupt flag is replayed to the caller");
        assertInstanceOf(InterruptedException.class, failure.getCause());
    }
}
