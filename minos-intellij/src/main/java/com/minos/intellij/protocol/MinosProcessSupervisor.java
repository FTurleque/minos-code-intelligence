package com.minos.intellij.protocol;

import com.intellij.execution.process.OSProcessUtil;

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
 * <p>Cancellation and timeout deliberately keep the CLI root alive while known descendants are
 * signalled first. This prevents the root from disappearing before its provider/build descendants
 * can be enumerated. The final forced phase delegates to IntelliJ's OS-aware
 * {@link OSProcessUtil#killProcessTree(Process)} (WinP/Windows recursive termination on Windows,
 * platform process-tree termination elsewhere) and retains bounded ProcessHandle sweeps only as a
 * fallback/verification layer.</p>
 */
final class MinosProcessSupervisor implements AutoCloseable {

    private static final int MAX_PROCESS_STREAM_BYTES = 16 * 1024 * 1024;
    private static final int DESCENDANT_SWEEP_COUNT = 3;
    private static final long DESCENDANT_SWEEP_DELAY_MILLIS = 100L;
    private static final long GRACEFUL_DESCENDANT_WAIT_MILLIS = 1_000L;
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

    /** Waits up to {@code millis} for the process to exit. */
    boolean waitFor(long millis) throws InterruptedException {
        return process.waitFor(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the complete MINOS process tree. The original IntelliJ cancellation exception is
     * rethrown unchanged. A null cause is the timeout/cleanup path and is reported by the caller.
     */
    void stop(Throwable cause) throws MinosProtocolException {
        stopping.set(true);
        List<Throwable> suppressed = new ArrayList<>();

        // Capture descendants while the root is definitely still alive. Handles remain valid even
        // if parent/child relationships disappear later in the shutdown sequence.
        List<ProcessHandle> snapshot = process.descendants().toList();

        // Give already-known descendants a bounded graceful opportunity without terminating the
        // CLI root first. Keeping the root alive prevents the original orphaning window.
        terminateDescendantsGracefully(snapshot);
        waitForDescendantsToSettle(GRACEFUL_DESCENDANT_WAIT_MILLIS, suppressed);

        // IntelliJ owns the OS-specific recursive termination implementation. On Windows this goes
        // through WinP/WinProcessManager rather than Java-only descendant traversal.
        boolean platformTreeKilled = false;
        try {
            platformTreeKilled = OSProcessUtil.killProcessTree(process);
        } catch (RuntimeException exception) {
            suppressed.add(new IOException("IntelliJ platform process-tree termination failed", exception));
        }
        if (!platformTreeKilled && process.isAlive()) {
            suppressed.add(new IOException("IntelliJ platform process-tree termination returned false"));
        }

        // Defense in depth: kill the pre-stop handles and perform bounded fresh sweeps. This also
        // covers environments where the native platform implementation is unavailable or partial.
        terminateDescendantsForcibly(snapshot, suppressed);
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(FORCED_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            suppressed.add(interrupted);
        }

        long survivors = process.descendants().filter(ProcessHandle::isAlive).count();
        if (process.isAlive() || survivors > 0) {
            suppressed.add(new IOException(
                    "MINOS process tree has " + (process.isAlive() ? 1 : 0) + " root + "
                            + survivors + " descendant(s) still alive after forced termination"));
        }

        closeQuietly(process.getInputStream(), suppressed);
        closeQuietly(process.getErrorStream(), suppressed);
        joinReaders(suppressed);
        propagate(cause, suppressed);
    }

    /**
     * Waits for reader threads to finish after normal process exit. Force-closes pipes after the
     * bounded join so a descendant cannot hold the IDE command indefinitely through inherited I/O.
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
        if (!suppressed.isEmpty()) {
            IOException ioe = new IOException("MINOS process output drain did not terminate");
            suppressed.forEach(ioe::addSuppressed);
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

    private static void terminateDescendantsGracefully(List<ProcessHandle> snapshot) {
        List<ProcessHandle> handles = new ArrayList<>(snapshot);
        handles.reversed().forEach(handle -> {
            if (handle.isAlive()) handle.destroy();
        });
    }

    private void waitForDescendantsToSettle(long maximumMillis, List<Throwable> suppressed) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumMillis);
        while (System.nanoTime() < deadline) {
            if (process.descendants().noneMatch(ProcessHandle::isAlive)) return;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                suppressed.add(interrupted);
                return;
            }
        }
    }

    private void terminateDescendantsForcibly(List<ProcessHandle> snapshot, List<Throwable> suppressed) {
        List<ProcessHandle> snapshotList = new ArrayList<>(snapshot);
        snapshotList.reversed().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        for (int sweep = 0; sweep < DESCENDANT_SWEEP_COUNT; sweep++) {
            List<ProcessHandle> fresh = new ArrayList<>(process.descendants().toList());
            fresh.reversed().forEach(handle -> {
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
            MinosProtocolException wrapped = new MinosProtocolException(
                    "MINOS process stopped: " + cause.getMessage(), cause);
            suppressed.forEach(wrapped::addSuppressed);
            throw wrapped;
        }
    }

    private void checkReadFailure() throws IOException {
        IOException failure = readFailure.get();
        if (failure != null) throw failure;
    }

    private static void readBounded(
            InputStream input,
            AtomicReference<byte[]> target,
            AtomicReference<IOException> failure
    ) {
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
