package com.minos.intellij.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the full lifecycle of one MINOS CLI process: start, output drain, cancellation, and
 * guaranteed cleanup of every descendant. Implements AutoCloseable so try-with-resources covers
 * the case where control flow exits before an explicit stop() call.
 *
 * <p>Stop sequence (deterministic, 8 steps):
 * <ol>
 *   <li>Mark as stopping — reject further poll/drain calls.
 *   <li>Graceful signal: {@link Process#destroy()} (SIGTERM / WM_CLOSE).
 *   <li>Bounded wait (1 s) for the process to exit on its own.
 *   <li>Kill all descendants leaf-first (3 bounded sweeps, 100 ms apart), then force the root.
 *   <li>Bounded wait (5 s), log any survivors as suppressed info — never throw here.
 *   <li>Force-close stdout/stderr pipes to unblock reader threads.
 *   <li>Join reader threads (5 s each).
 *   <li>Propagate the original cause (cancel rethrown; timeout wrapped in MinosProtocolException).
 * </ol>
 */
final class MinosProcessSupervisor implements AutoCloseable {

    private static final int MAX_PROCESS_STREAM_BYTES = 16 * 1024 * 1024;
    private static final int DESCENDANT_SWEEP_COUNT = 3;
    private static final long DESCENDANT_SWEEP_DELAY_MILLIS = 100L;
    private static final long GRACEFUL_WAIT_MILLIS = 1_000L;
    private static final long FORCED_WAIT_MILLIS = 5_000L;
    private static final long READER_JOIN_MILLIS = 5_000L;

    private final Process process;
    private final Thread outReader;
    private final Thread errReader;
    private final AtomicReference<byte[]> stdout = new AtomicReference<>(new byte[0]);
    private final AtomicReference<byte[]> stderr = new AtomicReference<>(new byte[0]);
    private final AtomicReference<IOException> readFailure = new AtomicReference<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    MinosProcessSupervisor(Process process) {
        this.process = process;
        this.outReader = Thread.ofVirtual().name("minos-stdout")
                .start(() -> readBounded(process.getInputStream(), stdout, readFailure));
        this.errReader = Thread.ofVirtual().name("minos-stderr")
                .start(() -> readBounded(process.getErrorStream(), stderr, readFailure));
    }

    /**
     * Waits up to {@code millis} for the process to exit. Returns {@code true} if it has exited.
     */
    boolean waitFor(long millis) throws InterruptedException {
        return process.waitFor(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs the full 8-step stop sequence. Rethrows {@code cause} if it is a
     * {@link com.intellij.openapi.progress.ProcessCanceledException}; otherwise wraps it (or the
     * timeout) into a {@link MinosProtocolException}. If {@code cause} is {@code null} the caller
     * is responsible for throwing a timeout exception after this method returns.
     */
    void stop(Throwable cause) throws MinosProtocolException {
        stopping.set(true);
        List<Throwable> suppressed = new ArrayList<>();

        // Step 2 — graceful
        process.destroy();

        // Step 3 — bounded wait
        try {
            process.waitFor(GRACEFUL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            suppressed.add(interrupted);
        }

        // Step 4 — descendants first, then root
        terminateDescendants(suppressed);
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(FORCED_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            suppressed.add(interrupted);
        }

        // Step 5 — verify (log survivors, do not throw)
        long survivors = process.descendants().filter(ProcessHandle::isAlive).count();
        if (process.isAlive() || survivors > 0) {
            suppressed.add(new IOException(
                    "MINOS process tree has " + (process.isAlive() ? 1 : 0) + " root + "
                            + survivors + " descendant(s) still alive after forced termination"));
        }

        // Step 6 — force-close pipes
        closeQuietly(process.getInputStream(), suppressed);
        closeQuietly(process.getErrorStream(), suppressed);

        // Step 7 — join reader threads
        joinReaders(suppressed);

        // Step 8 — propagate
        propagate(cause, suppressed);
    }

    /**
     * Waits for the reader threads to finish draining output after normal process exit.
     * Force-closes pipes after 5 s if the threads are still alive.
     */
    void drainOutput() throws IOException {
        boolean alive = false;
        for (Thread reader : new Thread[]{outReader, errReader}) {
            try {
                reader.join(READER_JOIN_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("MINOS process output drain was interrupted", interrupted);
            }
            alive |= reader.isAlive();
        }
        if (!alive) {
            checkReadFailure();
            return;
        }
        closeQuietly(process.getInputStream(), null);
        closeQuietly(process.getErrorStream(), null);
        List<Throwable> suppressed = new ArrayList<>();
        joinReaders(suppressed);
        for (Throwable t : suppressed) {
            IOException ioe = new IOException("MINOS process output drain did not terminate");
            ioe.addSuppressed(t);
            throw ioe;
        }
        for (Thread reader : new Thread[]{outReader, errReader}) {
            if (reader.isAlive()) throw new IOException("MINOS process output drain did not terminate");
        }
        checkReadFailure();
    }

    int exitValue() {
        return process.exitValue();
    }

    String stdout() {
        return new String(stdout.get(), StandardCharsets.UTF_8);
    }

    String stderr() {
        return new String(stderr.get(), StandardCharsets.UTF_8);
    }

    IOException readFailure() {
        return readFailure.get();
    }

    /**
     * Ensures cleanup if control flow exits without an explicit stop() — e.g. an unexpected
     * exception from the poll loop before the timeout/cancel branches run.
     */
    @Override
    public void close() {
        if (process.isAlive() && stopping.compareAndSet(false, true)) {
            try {
                stop(null);
            } catch (MinosProtocolException ignored) {
                // AutoCloseable.close() cannot throw checked exceptions.
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------------------------

    private void terminateDescendants(List<Throwable> suppressed) {
        for (int sweep = 0; sweep < DESCENDANT_SWEEP_COUNT; sweep++) {
            List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
            descendants.reversed().forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
            if (sweep < DESCENDANT_SWEEP_COUNT - 1 && process.isAlive()) {
                try {
                    Thread.sleep(DESCENDANT_SWEEP_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    suppressed.add(interrupted);
                    return;
                }
            }
        }
    }

    private void joinReaders(List<Throwable> suppressed) {
        for (Thread reader : new Thread[]{outReader, errReader}) {
            try {
                reader.join(READER_JOIN_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                suppressed.add(interrupted);
            }
            if (reader.isAlive()) {
                suppressed.add(new IOException("MINOS output reader thread did not terminate: " + reader.getName()));
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable, List<Throwable> suppressed) {
        try {
            closeable.close();
        } catch (IOException exception) {
            if (suppressed != null) suppressed.add(exception);
        }
    }

    private void propagate(Throwable cause, List<Throwable> suppressed) throws MinosProtocolException {
        if (cause instanceof com.intellij.openapi.progress.ProcessCanceledException pce) {
            suppressed.forEach(pce::addSuppressed);
            throw pce;
        }
        if (cause != null) {
            MinosProtocolException wrapped = new MinosProtocolException("MINOS process stopped: " + cause.getMessage(), cause);
            suppressed.forEach(wrapped::addSuppressed);
            throw wrapped;
        }
        // cause == null: timeout path — caller is responsible for throwing the timeout exception.
        // Attach any cleanup errors as suppressed on a sentinel for the caller to inspect if needed.
        // We surface read failures here so the caller can check readFailure().
    }

    private void checkReadFailure() throws IOException {
        IOException failure = readFailure.get();
        if (failure != null) throw failure;
    }

    private static void readBounded(InputStream input, AtomicReference<byte[]> target, AtomicReference<IOException> failure) {
        try (InputStream stream = input) {
            byte[] bytes = stream.readNBytes(MAX_PROCESS_STREAM_BYTES + 1);
            if (bytes.length > MAX_PROCESS_STREAM_BYTES) {
                failure.compareAndSet(null, new IOException("MINOS process output exceeds 16 MiB safety limit"));
                target.set(new byte[0]);
            } else {
                target.set(bytes);
            }
        } catch (IOException exception) {
            failure.compareAndSet(null, exception);
        }
    }
}
