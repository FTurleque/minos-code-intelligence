package com.minos.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JGitRemoteRepositoryMaterializerLockTest {

    @TempDir
    Path root;

    @Test
    void boundedFileLockFailsClosedWhenAnotherHolderDoesNotRelease() throws Exception {
        Path file = root.resolve("remote-cache.lock");
        try (FileChannel owner = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = owner.lock();
             FileChannel contender = FileChannel.open(
                     file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            IOException failure = assertThrows(IOException.class, () ->
                    JGitRemoteRepositoryMaterializer.acquireFileLock(
                            contender, Duration.ofMillis(100), "test remote cache lock"));
            assertTrue(failure.getMessage().contains("timed out"));
        }
    }

    @Test
    void boundedFileLockCanBeAcquiredAfterPreviousHolderReleases() throws Exception {
        Path file = root.resolve("released-cache.lock");
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = JGitRemoteRepositoryMaterializer.acquireFileLock(
                     channel, Duration.ofSeconds(1), "test remote cache lock")) {
            assertTrue(ignored.isValid());
        }
    }
}
