package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;
import com.minos.testsupport.ParkedThreads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrency correctness tests for {@link FileSemanticVectorStore#replaceConditionally}.
 * Verifies the MINOS-03 invariant: stale writers cannot overwrite a newer committed index.
 */
class FileSemanticSyncConsistencyTest {

    private static final String BOUNDED_FILE_LEASE = "com.minos.io.BoundedFileLease";

    // -------------------------------------------------------------------------
    // Unit: conditional replace rejects stale snapshot
    // -------------------------------------------------------------------------

    @Test
    void replaceConditionallyThrowsWhenActiveSnapshotChanged(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectId = UUID.randomUUID().toString();

        // Commit snap-2 as the durable index
        store.replace(snap(projectId, "snap-2"));

        // Try to commit snap-1 while active is snap-2 → must be rejected
        assertThrows(StaleSemanticSyncException.class, () ->
                store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                        () -> Optional.of("snap-2")));

        // Durable index is still snap-2
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId());
    }

    @Test
    void replaceConditionallySucceedsWhenSnapshotIsStillCurrent(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectId = UUID.randomUUID().toString();

        store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                () -> Optional.of("snap-1"));

        assertEquals("snap-1", store.metadata(projectId).orElseThrow().snapshotId());
    }

    @Test
    void replaceConditionallyThrowsWhenNoActiveSnapshotExists(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectId = UUID.randomUUID().toString();

        assertThrows(StaleSemanticSyncException.class, () ->
                store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                        Optional::empty)); // active pointer absent (project deleted/expired)
    }

    // -------------------------------------------------------------------------
    // Same-JVM: two threads racing on the same project — loser is rejected
    // -------------------------------------------------------------------------

    @Test
    void sameJvmRaceForSameProjectSerializesWriters(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectId = UUID.randomUUID().toString();

        // Simulate: slow writer A read snap-1, fast writer B has already committed snap-2.
        // A holds the lock first via its replaceConditionally call, but the active reader is
        // controlled to return snap-2 for A (stale) and snap-1 for the setup-read below.

        AtomicBoolean aSawStale = new AtomicBoolean(false);
        AtomicBoolean bSucceeded = new AtomicBoolean(false);
        CountDownLatch aHoldsLock = new CountDownLatch(1);

        // Thread A: enters replaceConditionally but blocks before the active-reader completes
        // We simulate the "embedding delay" by having the reader gate on a latch
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        // Thread A: holds the per-project lock while "re-verifying" (reader blocks on latch)
        Thread writerA = daemon("semantic-sync-writer-a", () -> {
            try {
                store.replaceConditionally(snap(projectId, "snap-1"), "snap-1", () -> {
                    aHoldsLock.countDown(); // signal: A is inside the lock, reader called
                    try { releaseA.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return Optional.of("snap-2"); // active promoted to snap-2 while A was embedding
                });
            } catch (StaleSemanticSyncException e) {
                aSawStale.set(true);
            } catch (Throwable t) {
                unexpectedError.compareAndSet(null, t);
            }
        });
        // Thread B: tries to commit snap-2 while A holds the lock
        Thread writerB = daemon("semantic-sync-writer-b", () -> {
            try {
                store.replaceConditionally(snap(projectId, "snap-2"), "snap-2",
                        () -> Optional.of("snap-2"));
                bSucceeded.set(true);
            } catch (Throwable t) {
                unexpectedError.compareAndSet(null, t);
            }
        });
        try {
            writerA.start();
            // Wait for A to be inside the lock (reader blocked)
            assertTrue(aHoldsLock.await(10, TimeUnit.SECONDS));

            writerB.start();
            // B is observed waiting for the lease A holds, not assumed blocked.
            ParkedThreads.awaitParkedIn(writerB, BOUNDED_FILE_LEASE, Duration.ofSeconds(10));
            assertFalse(bSucceeded.get(), "thread B must not commit while A holds the lock");
        } finally {
            // Release A (its reader returns snap-2 ≠ expected snap-1 → StaleSemanticSyncException)
            releaseA.countDown();
            writerA.join(TimeUnit.SECONDS.toMillis(10));
            // After A releases the lock, B should proceed and succeed
            writerB.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertFalse(writerA.isAlive(), "thread A must complete");
        assertFalse(writerB.isAlive(), "thread B must complete");
        assertTrue(aSawStale.get(), "thread A must have seen StaleSemanticSyncException");
        assertTrue(bSucceeded.get(), "thread B must succeed after A releases the lock");
        assertNull(unexpectedError.get(), "no unexpected errors");
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId(),
                "durable index must reflect snap-2 (the winner)");
    }

    // -------------------------------------------------------------------------
    // Same-JVM: two different projects are not serialized by each other
    // -------------------------------------------------------------------------

    @Test
    void differentProjectsAreNotSerializedBySameLock(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectX = UUID.randomUUID().toString();
        String projectY = UUID.randomUUID().toString();

        CountDownLatch xHoldsLock = new CountDownLatch(1);
        CountDownLatch xProceed = new CountDownLatch(1);
        AtomicBoolean ySucceeded = new AtomicBoolean(false);
        AtomicReference<Throwable> errors = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            // X holds its per-project lock with a blocking reader
            Future<?> futureX = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectX, "snap-x"), "snap-x", () -> {
                        xHoldsLock.countDown();
                        try { xProceed.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return Optional.of("snap-x");
                    });
                } catch (Throwable t) { errors.compareAndSet(null, t); }
            });

            assertTrue(xHoldsLock.await(10, TimeUnit.SECONDS));

            // Y must complete while X is still holding its lock
            Future<?> futureY = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectY, "snap-y"), "snap-y",
                            () -> Optional.of("snap-y"));
                    ySucceeded.set(true);
                } catch (Throwable t) { errors.compareAndSet(null, t); }
            });
            futureY.get(10, TimeUnit.SECONDS);

            assertTrue(ySucceeded.get(), "project Y must complete independently of project X");
            assertNull(errors.get(), "no unexpected errors");

            xProceed.countDown();
            futureX.get(10, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Same-JVM: exception in active-reader releases the lock
    // -------------------------------------------------------------------------

    @Test
    void lockIsReleasedWhenActiveReaderThrows(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectId = UUID.randomUUID().toString();

        SemanticVectorStore.IndexSnapshot first = snap(projectId, "snap-1");
        IOException failure = assertThrows(IOException.class, () ->
                store.replaceConditionally(first, "snap-1",
                        () -> { throw new IOException("simulated I/O failure"); }));
        assertEquals("simulated I/O failure", failure.getMessage());

        // Lock must be released — a follow-up call must proceed
        store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                () -> Optional.of("snap-1"));
        assertEquals("snap-1", store.metadata(projectId).orElseThrow().snapshotId());
    }

    // -------------------------------------------------------------------------
    // Cross-JVM: subprocess holds the file lock while we try replaceConditionally
    // -------------------------------------------------------------------------

    @Test
    @Timeout(120)
    void crossJvmFileLockBlocksConditionalReplaceUntilReleased(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp);
        String projectId = UUID.randomUUID().toString();

        // Compute the expected lock file path (same formula as FileSemanticVectorStore)
        Path lockFile = temp.resolve(".sync-locks").resolve(projectId + ".lock");

        try (LockHolder holder = LockHolder.start(SemanticSyncLockHolderProcess.class.getName(), lockFile.toString())) {
            // A holder that does not signal READY fails the proof; it is never skipped.
            holder.awaitReady();

            // Our JVM tries replaceConditionally → channel.lock() must block on the subprocess's file lock
            AtomicBoolean commitSucceeded = new AtomicBoolean(false);
            AtomicReference<Throwable> syncError = new AtomicReference<>();
            CountDownLatch attempted = new CountDownLatch(1);

            Thread syncThread = daemon("semantic-sync-cross-jvm", () -> {
                attempted.countDown();
                try {
                    store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                            () -> Optional.of("snap-1"));
                    commitSucceeded.set(true);
                } catch (IOException e) {
                    syncError.set(e);
                }
            });
            syncThread.start();

            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            // The thread is observed retrying the file lock held by the other JVM, not assumed blocked after a delay.
            ParkedThreads.awaitParkedIn(syncThread, BOUNDED_FILE_LEASE, Duration.ofSeconds(5));
            assertFalse(commitSucceeded.get(),
                    "replaceConditionally must be blocked while subprocess holds the cross-JVM file lock");

            // Release the subprocess lock by closing its stdin
            assertTrue(holder.release(), "subprocess must exit after stdin closes");

            // Now our sync thread should unblock and complete
            syncThread.join(10_000);
            assertTrue(commitSucceeded.get(),
                    "replaceConditionally must succeed after cross-JVM file lock is released: " + syncError.get());
        }
    }

    @Test
    @Timeout(60)
    void lockHolderThatCannotStartFailsTheProofInsteadOfSkippingIt() throws Exception {
        String missingMain = SemanticSyncLockHolderProcess.class.getName() + "Missing";
        try (LockHolder holder = LockHolder.start(missingMain)) {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class, holder::awaitReady);
            // The launcher exits with 1 when the main class cannot be found.
            assertEquals(1, holder.exitCode(), failure.getMessage());
            assertTrue(failure.getMessage().contains("exit code 1)"), failure.getMessage());
            assertTrue(failure.getMessage().contains(missingMain), failure.getMessage());
        }
    }

    @Test
    @Timeout(60)
    void lockHolderFailureKeepsOnlyTheBoundedTailOfALargeErrorOutput() throws Exception {
        try (LockHolder holder = LockHolder.start(NoisyFailingHolderProcess.class.getName())) {
            // Without a continuous drain, the child would block on its full stderr pipe and never exit.
            AssertionFailedError failure = assertThrows(AssertionFailedError.class, holder::awaitReady);
            String message = failure.getMessage();
            assertEquals(NoisyFailingHolderProcess.EXIT_CODE, holder.exitCode(), message);
            assertTrue(message.contains("exit code " + NoisyFailingHolderProcess.EXIT_CODE + ")"), message);
            assertTrue(message.contains(NoisyFailingHolderProcess.LAST_LINE), message);
            assertFalse(message.contains(NoisyFailingHolderProcess.FIRST_LINE), message);
            assertTrue(message.length() < LockHolder.STDERR_TAIL_BYTES + 512,
                    () -> "failure message must stay bounded, got " + message.length() + " characters");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * A child JVM started by a cross-JVM test. Its environment is built from scratch (nothing is inherited),
     * its stderr is drained continuously into a bounded tail so it never blocks on a full pipe, and a child
     * that does not signal READY fails the test with its exit code and that tail.
     */
    private static final class LockHolder implements AutoCloseable {

        static final int STDERR_TAIL_BYTES = 4096;
        private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);
        private static final Charset CHILD_CHARSET =
                Charset.forName(System.getProperty("native.encoding", "UTF-8"));

        private final Process process;
        private final BufferedReader stdout;
        private final Thread stderrDrain;
        private final byte[] tail = new byte[STDERR_TAIL_BYTES];
        private long written;

        private LockHolder(Process process) {
            this.process = process;
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), CHILD_CHARSET));
            this.stderrDrain = daemon("lock-holder-stderr", this::drainStderr);
            this.stderrDrain.start();
        }

        static LockHolder start(String mainClass, String... args) throws IOException {
            Optional<String> javaCommand = ProcessHandle.current().info().command();
            assertTrue(javaCommand.isPresent(), "JVM command path unavailable: the lock holder cannot be started");
            String classpath = System.getProperty("java.class.path", "");
            assertFalse(classpath.isBlank(), "java.class.path unavailable: the lock holder cannot be started");

            List<String> command = new ArrayList<>(List.of(javaCommand.get(), "-cp", classpath, mainClass));
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> environment = builder.environment();
            environment.clear();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                // The only variable a JVM needs to start on Windows.
                String systemRoot = System.getenv("SystemRoot");
                if (systemRoot != null) environment.put("SystemRoot", systemRoot);
            }
            return new LockHolder(builder.start());
        }

        /** Returns once the child signals READY; otherwise fails with its exit code and stderr tail. */
        void awaitReady() throws IOException, InterruptedException {
            String ready = stdout.readLine();
            if ("READY".equals(ready)) return;
            String exit;
            if (process.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                exit = "exit code " + process.exitValue();
                stderrDrain.join(EXIT_TIMEOUT.toMillis());
            } else {
                exit = "still running after " + EXIT_TIMEOUT;
            }
            fail("lock holder did not signal READY (got: " + ready + ", " + exit + "); stderr tail:\n" + stderrTail());
        }

        /** Returns the exit code of a child that has already exited. */
        int exitCode() {
            return process.exitValue();
        }

        /** Closes the child's stdin, which makes it release its lock and exit; reports whether it exited. */
        boolean release() throws IOException, InterruptedException {
            process.getOutputStream().close();
            return process.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws IOException {
            process.destroyForcibly();
            stdout.close();
        }

        private void drainStderr() {
            byte[] chunk = new byte[1024];
            try (InputStream stderr = process.getErrorStream()) {
                int read;
                while ((read = stderr.read(chunk)) != -1) append(chunk, read);
            } catch (IOException closed) {
                // The stream is closed when the child is destroyed: the tail read so far is kept.
            }
        }

        private synchronized void append(byte[] chunk, int length) {
            for (int index = 0; index < length; index++) {
                tail[(int) (written % tail.length)] = chunk[index];
                written++;
            }
        }

        private synchronized String stderrTail() {
            int size = (int) Math.min(written, tail.length);
            byte[] ordered = new byte[size];
            long first = written - size;
            for (int index = 0; index < size; index++) {
                ordered[index] = tail[(int) ((first + index) % tail.length)];
            }
            return new String(ordered, CHILD_CHARSET);
        }
    }

    /** A daemon thread, so a failed assertion never leaves the test JVM waiting for it. */
    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    private static SemanticVectorStore.IndexSnapshot snap(String projectId, String snapshotId) {
        String stableKey = "symbol:fixture:" + snapshotId;
        SemanticDocument document = new SemanticDocument(
                "id:" + snapshotId, stableKey, projectId, snapshotId,
                SemanticDocumentKind.SYMBOL, "fixture", "src/Fixture.java",
                1, 1, "content for " + snapshotId, "cs-" + snapshotId);
        SemanticVector vector = new SemanticVector(stableKey, List.of(1.0, 0.0));
        return new SemanticVectorStore.IndexSnapshot(
                projectId, snapshotId, "provider", "model-v1", 2,
                System.currentTimeMillis(),
                List.of(new SemanticVectorStore.IndexedDocument(document, vector)));
    }
}
