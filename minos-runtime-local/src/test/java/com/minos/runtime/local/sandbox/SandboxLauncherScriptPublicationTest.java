package com.minos.runtime.local.sandbox;

import com.minos.io.DurableAtomicFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher is published exclusively and with a bounded retry (AUD-SEC-03): a copy another process published
 * meanwhile is kept, never replaced; a transient refusal (an antivirus holding the new file) is retried; a filesystem
 * without hard links fails with the message of the primitive and no path.
 */
class SandboxLauncherScriptPublicationTest {

    private static final SandboxLauncherScript.Publisher REAL =
            (source, target) -> DurableAtomicFile.publish(source, target, "sandbox launcher publication");

    private static Path partial(Path directory, String content) throws IOException {
        return Files.writeString(directory.resolve(".launcher-" + System.nanoTime() + ".ps1"), content,
                StandardCharsets.UTF_8);
    }

    @Test
    void aFreeTargetReceivesTheLauncher(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("launcher.ps1");

        SandboxLauncherScript.publishWithRetry(partial(directory, "mine"), target, REAL, 0L);

        assertEquals("mine", Files.readString(target));
    }

    @Test
    void aLauncherPublishedMeanwhileIsKeptNotReplaced(@TempDir Path directory) throws Exception {
        Path target = Files.writeString(directory.resolve("launcher.ps1"), "theirs", StandardCharsets.UTF_8);

        SandboxLauncherScript.publishWithRetry(partial(directory, "mine"), target, REAL, 0L);

        assertEquals("theirs", Files.readString(target), "the verification decides about it, the publication does not");
    }

    @Test
    void twoPublicationsAtTheSameTimeLeaveOneCompleteLauncher(@TempDir Path directory) throws Exception {
        for (int round = 0; round < 20; round++) {
            Path target = directory.resolve("launcher-" + round + ".ps1");
            String first = "first launcher " + "a".repeat(2000);
            String second = "second launcher " + "b".repeat(2000);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Object> one = () -> publish(directory, target, first);
                Callable<Object> two = () -> publish(directory, target, second);
                for (Future<Object> done : pool.invokeAll(java.util.List.of(one, two))) done.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            String winner = Files.readString(target);
            assertTrue(winner.equals(first) || winner.equals(second), "round " + round + ": " + winner.length());
        }
    }

    private static Object publish(Path directory, Path target, String content) throws IOException {
        SandboxLauncherScript.publishWithRetry(partial(directory, content), target, REAL, 0L);
        return null;
    }

    @Test
    void aTransientRefusalIsRetriedUpToTheBound(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("launcher.ps1");
        AtomicInteger attempts = new AtomicInteger();
        SandboxLauncherScript.Publisher flaky = (source, to) -> {
            if (attempts.incrementAndGet() <= 4) throw new FileSystemException(to.toString());
            REAL.publish(source, to);
        };

        SandboxLauncherScript.publishWithRetry(partial(directory, "mine"), target, flaky, 0L);

        assertEquals(5, attempts.get());
        assertEquals("mine", Files.readString(target));
    }

    @Test
    void aRefusalWhichNeverEndsFailsAfterTheBoundWithAMessageWithoutPath(@TempDir Path directory) {
        AtomicInteger attempts = new AtomicInteger();
        SandboxLauncherScript.Publisher refusing = (source, to) -> {
            attempts.incrementAndGet();
            throw new FileSystemException(to.toString());
        };

        IOException failure = assertThrows(IOException.class, () -> SandboxLauncherScript.publishWithRetry(
                partial(directory, "mine"), directory.resolve("launcher.ps1"), refusing, 0L));

        assertEquals(5, attempts.get());
        assertEquals("sandbox launcher could not be published", failure.getMessage());
    }

    @Test
    void aSharingViolationWrappedByThePrimitiveIsRetriedToo(@TempDir Path directory) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SandboxLauncherScript.Publisher sharing = (source, to) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("filesystem does not support required exclusive sandbox launcher publication: x",
                        new FileSystemException(to.toString()));
            }
            REAL.publish(source, to);
        };

        SandboxLauncherScript.publishWithRetry(
                partial(directory, "mine"), directory.resolve("launcher.ps1"), sharing, 0L);

        assertEquals(2, attempts.get());
    }

    @Test
    void aFilesystemWithoutHardLinksFailsWithThePrimitiveMessage(@TempDir Path directory) {
        SandboxLauncherScript.Publisher noLinks = (source, to) -> {
            throw new IOException("filesystem does not support required exclusive sandbox launcher publication: "
                    + to.getFileName(), new FileSystemException("no hard link"));
        };

        IOException failure = assertThrows(IOException.class, () -> SandboxLauncherScript.publishWithRetry(
                partial(directory, "mine"), directory.resolve("launcher.ps1"), noLinks, 0L));

        assertTrue(failure.getMessage().startsWith("filesystem does not support required exclusive"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(directory.toString()), failure.getMessage());
    }

    @Test
    void anOtherFailureIsNotRetried(@TempDir Path directory) {
        AtomicInteger attempts = new AtomicInteger();
        SandboxLauncherScript.Publisher broken = (source, to) -> {
            attempts.incrementAndGet();
            throw new IOException("disk failure");
        };

        assertThrows(IOException.class, () -> SandboxLauncherScript.publishWithRetry(
                partial(directory, "mine"), directory.resolve("launcher.ps1"), broken, 0L));

        assertEquals(1, attempts.get());
    }

    @Test
    void anExistingTargetAfterAFileAlreadyExistsRefusalIsAccepted(@TempDir Path directory) throws Exception {
        Path target = Files.writeString(directory.resolve("launcher.ps1"), "theirs", StandardCharsets.UTF_8);
        SandboxLauncherScript.Publisher exists = (source, to) -> {
            throw new FileAlreadyExistsException(to.toString());
        };

        SandboxLauncherScript.publishWithRetry(partial(directory, "mine"), target, exists, 0L);

        assertEquals("theirs", Files.readString(target));
    }
}
