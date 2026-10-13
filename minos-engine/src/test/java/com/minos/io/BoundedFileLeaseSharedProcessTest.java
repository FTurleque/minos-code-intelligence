package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le bail de lecture face à un <strong>autre processus</strong> : un lecteur et un écrivain s'excluent, deux lecteurs
 * non. Dans une même JVM tout recouvrement de verrou est refusé par Java, lecteurs compris : seule une seconde JVM
 * prouve la sémantique du système.
 */
class BoundedFileLeaseSharedProcessTest {

    private static final Duration SHORT = Duration.ofMillis(200);
    private static final Duration LONG = Duration.ofSeconds(20);

    @TempDir
    Path root;

    @Test
    void aReaderIsExcludedWhileAnotherProcessWritesAndAnswersOnceItIsDone() throws Exception {
        Path lockFile = root.resolve("lease.lock");
        ReentrantLock jvmLock = new ReentrantLock();

        try (Holder writer = Holder.start(lockFile, "exclusive")) {
            IOException failure = assertThrows(IOException.class,
                    () -> BoundedFileLease.acquireShared(lockFile, jvmLock, SHORT, "reader lease"));
            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
            assertFalse(jvmLock.isLocked(), "a reader that timed out holds nothing");
            writer.release();
        }

        try (BoundedFileLease ignored = BoundedFileLease.acquireShared(lockFile, jvmLock, LONG, "reader lease")) {
            assertTrue(jvmLock.isHeldByCurrentThread());
        }
    }

    @Test
    void twoReadersOfTwoProcessesDoNotExcludeEachOther() throws Exception {
        Path lockFile = createLockFile();

        try (Holder otherReader = Holder.start(lockFile, "shared");
             BoundedFileLease ignored = BoundedFileLease.acquireShared(
                     lockFile, new ReentrantLock(), SHORT, "reader lease")) {
            assertTrue(otherReader.isHolding(), "the other reader still holds its lease");
        }
    }

    @Test
    void aWriterIsExcludedWhileAnotherProcessReadsAndProceedsOnceItIsDone() throws Exception {
        Path lockFile = createLockFile();
        ReentrantLock jvmLock = new ReentrantLock();

        try (Holder reader = Holder.start(lockFile, "shared")) {
            IOException failure = assertThrows(IOException.class,
                    () -> BoundedFileLease.acquire(lockFile, jvmLock, SHORT, "writer lease"));
            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
            reader.release();
        }

        try (BoundedFileLease ignored = BoundedFileLease.acquire(lockFile, jvmLock, LONG, "writer lease")) {
            assertTrue(jvmLock.isHeldByCurrentThread());
        }
    }

    /** Un lecteur ne crée pas le fichier de verrou : c'est un écrivain qui le fait exister. */
    private Path createLockFile() throws IOException {
        Path lockFile = root.resolve("lease.lock");
        BoundedFileLease.acquire(lockFile, new ReentrantLock(), LONG, "writer lease").close();
        return lockFile;
    }

    /** Une seconde JVM qui tient un bail jusqu'à {@link #release()}. */
    private static final class Holder implements AutoCloseable {
        private final Process process;

        private Holder(Process process) {
            this.process = process;
        }

        static Holder start(Path lockFile, String mode) throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Process process = new ProcessBuilder(List.of(java.toString(), "-cp",
                    System.getProperty("java.class.path"), BoundedFileLeaseHolderChild.class.getName(),
                    lockFile.toString(), mode))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            BufferedReader output = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line = output.readLine();
            if (!BoundedFileLeaseHolderChild.HELD.equals(line)) {
                process.destroyForcibly();
                throw new IOException("the child process did not take its lease: " + line);
            }
            return new Holder(process);
        }

        boolean isHolding() {
            return process.isAlive();
        }

        void release() throws Exception {
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the child process must end once released");
            assertEquals(0, process.exitValue());
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) release();
        }
    }
}
