package com.minos.intellij.protocol;

import com.intellij.execution.process.OSProcessUtil;
import com.intellij.openapi.progress.ProcessCanceledException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the full lifecycle of one MINOS CLI process: start, output drain, cancellation, and
 * cleanup of every process that was observed as owned by the command.
 *
 * <p>Ownership is tracked continuously while the root is alive instead of being reconstructed
 * only when cleanup starts. This preserves descendants that become re-parented after the root
 * exits. Handles are keyed by PID plus process start instant so PID reuse can never turn a stale
 * ownership record into permission to terminate an unrelated process.</p>
 */
final class MinosProcessSupervisor implements AutoCloseable {

    private static final int MAX_PROCESS_STREAM_BYTES = 16 * 1024 * 1024;
    private static final int DESCENDANT_SWEEP_COUNT = 3;
    private static final long DESCENDANT_SWEEP_DELAY_MILLIS = 100L;
    private static final long GRACEFUL_DESCENDANT_WAIT_MILLIS = 1_000L;
    private static final long FORCED_WAIT_MILLIS = 5_000L;
    private static final long READER_JOIN_MILLIS = 5_000L;
    private static final long OWNERSHIP_POLL_MILLIS = 10L;
    private static final long POST_ROOT_EXIT_TRACK_MILLIS = 250L;

    private final Process process;
    private final Thread outReader;
    private final Thread errReader;
    private final Thread ownershipTracker;
    private final AtomicReference<byte[]> stdout = new AtomicReference<>(new byte[0]);
    private final AtomicReference<byte[]> stderr = new AtomicReference<>(new byte[0]);
    private final AtomicReference<IOException> readFailure = new AtomicReference<>();
    private final AtomicReference<IOException> ownershipFailure = new AtomicReference<>();
    private final AtomicBoolean trackerStop = new AtomicBoolean(false);
    private final AtomicBoolean terminationComplete = new AtomicBoolean(false);
    private final Map<ProcessIdentity, ProcessHandle> ownedHandles = new LinkedHashMap<>();

    MinosProcessSupervisor(Process process) {
        this.process = process;
        remember(process.descendants().toList());
        this.ownershipTracker = Thread.ofVirtual().name("minos-process-ownership")
                .start(this::trackOwnership);
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
     * Stops the complete MINOS process tree. Cancellation is rethrown unchanged. A null cause is
     * the timeout/explicit-cleanup path; any cleanup failure is then surfaced as a protocol error.
     */
    synchronized void stop(Throwable cause) throws MinosProtocolException {
        if (terminationComplete.get()) {
            if (cause instanceof ProcessCanceledException canceled) throw canceled;
            if (cause != null) throw new MinosProtocolException("MINOS process stopped: " + cause.getMessage(), cause);
            return;
        }

        List<Throwable> failures = new ArrayList<>();
        remember(process.descendants().toList());
        List<ProcessHandle> snapshot = rememberedHandles();

        terminateDescendantsGracefully(snapshot);
        waitForDescendantsToSettle(GRACEFUL_DESCENDANT_WAIT_MILLIS, failures);
        remember(process.descendants().toList());

        boolean platformTreeKilled = false;
        try {
            platformTreeKilled = OSProcessUtil.killProcessTree(process);
        } catch (RuntimeException exception) {
            failures.add(new IOException("IntelliJ platform process-tree termination failed", exception));
        }
        if (!platformTreeKilled && process.isAlive()) {
            failures.add(new IOException("IntelliJ platform process-tree termination returned false"));
        }

        terminateDescendantsForcibly(rememberedHandles(), failures);
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(FORCED_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }

        remember(process.descendants().toList());
        stopOwnershipTracker(failures);
        terminateRememberedSurvivors();
        long survivors = rememberedHandles().stream().filter(this::isSameOwnedProcessAlive).count();
        if (process.isAlive() || survivors > 0) {
            failures.add(new IOException(
                    "MINOS process tree has " + (process.isAlive() ? 1 : 0) + " root + "
                            + survivors + " owned descendant(s) still alive after forced termination"));
        }

        IOException trackerFailure = ownershipFailure.get();
        if (trackerFailure != null) failures.add(trackerFailure);
        closeQuietly(process.getInputStream(), failures);
        closeQuietly(process.getErrorStream(), failures);
        joinReaders(failures);

        boolean readersStopped = !outReader.isAlive() && !errReader.isAlive();
        boolean processesStopped = !process.isAlive()
                && rememberedHandles().stream().noneMatch(this::isSameOwnedProcessAlive);
        boolean trackerStopped = !ownershipTracker.isAlive();
        terminationComplete.set(processesStopped && readersStopped && trackerStopped);
        propagate(cause, failures);
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
        List<Throwable> failures = new ArrayList<>();
        joinReaders(failures);
        if (!failures.isEmpty()) {
            IOException ioe = new IOException("MINOS process output drain did not terminate");
            failures.forEach(ioe::addSuppressed);
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
    public void close() throws MinosProtocolException {
        if (!terminationComplete.get()) stop(null);
    }

    private void trackOwnership() {
        long rootExitObservedAt = -1L;
        try {
            while (!trackerStop.get()) {
                remember(process.descendants().toList());
                if (!process.isAlive()) {
                    if (rootExitObservedAt < 0L) rootExitObservedAt = System.nanoTime();
                    long elapsed = System.nanoTime() - rootExitObservedAt;
                    if (elapsed >= TimeUnit.MILLISECONDS.toNanos(POST_ROOT_EXIT_TRACK_MILLIS)) return;
                }
                Thread.sleep(OWNERSHIP_POLL_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            if (!trackerStop.get()) {
                Thread.currentThread().interrupt();
                ownershipFailure.compareAndSet(null,
                        new IOException("MINOS process ownership tracking was interrupted", interrupted));
            }
        } catch (RuntimeException failure) {
            ownershipFailure.compareAndSet(null,
                    new IOException("MINOS process ownership tracking failed", failure));
        }
    }

    private void stopOwnershipTracker(List<Throwable> failures) {
        trackerStop.set(true);
        ownershipTracker.interrupt();
        try {
            ownershipTracker.join(READER_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        if (ownershipTracker.isAlive()) {
            failures.add(new IOException("MINOS process ownership tracker did not terminate"));
        }
    }

    private synchronized void remember(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            ProcessIdentity identity = identity(handle);
            ownedHandles.putIfAbsent(identity, handle);
        }
    }

    private synchronized List<ProcessHandle> rememberedHandles() {
        return new ArrayList<>(ownedHandles.values());
    }

    private static ProcessIdentity identity(ProcessHandle handle) {
        return new ProcessIdentity(handle.pid(), handle.info().startInstant());
    }

    private boolean isSameOwnedProcessAlive(ProcessHandle remembered) {
        ProcessIdentity expected = identity(remembered);
        return ProcessHandle.of(expected.pid())
                .filter(current -> identity(current).equals(expected))
                .map(ProcessHandle::isAlive)
                .orElse(false);
    }

    private static void terminateDescendantsGracefully(List<ProcessHandle> snapshot) {
        List<ProcessHandle> handles = new ArrayList<>(snapshot);
        handles.reversed().forEach(handle -> {
            ProcessIdentity expected = identity(handle);
            ProcessHandle.of(expected.pid())
                    .filter(current -> identity(current).equals(expected))
                    .filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroy);
        });
    }

    private void waitForDescendantsToSettle(long maximumMillis, List<Throwable> failures) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumMillis);
        while (System.nanoTime() < deadline) {
            remember(process.descendants().toList());
            if (rememberedHandles().stream().noneMatch(this::isSameOwnedProcessAlive)) return;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
                return;
            }
        }
    }

    private void terminateDescendantsForcibly(List<ProcessHandle> snapshot, List<Throwable> failures) {
        List<ProcessHandle> snapshotList = new ArrayList<>(snapshot);
        snapshotList.reversed().forEach(this::destroySameProcessForcibly);
        for (int sweep = 0; sweep < DESCENDANT_SWEEP_COUNT; sweep++) {
            List<ProcessHandle> fresh = new ArrayList<>(process.descendants().toList());
            remember(fresh);
            fresh.reversed().forEach(this::destroySameProcessForcibly);
            terminateRememberedSurvivors();
            if (sweep < DESCENDANT_SWEEP_COUNT - 1
                    && (process.isAlive() || rememberedHandles().stream().anyMatch(this::isSameOwnedProcessAlive))) {
                try {
                    Thread.sleep(DESCENDANT_SWEEP_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.add(interrupted);
                    return;
                }
            }
        }
    }

    private void terminateRememberedSurvivors() {
        List<ProcessHandle> handles = rememberedHandles();
        handles.reversed().forEach(this::destroySameProcessForcibly);
    }

    private void destroySameProcessForcibly(ProcessHandle remembered) {
        ProcessIdentity expected = identity(remembered);
        ProcessHandle.of(expected.pid())
                .filter(current -> identity(current).equals(expected))
                .filter(ProcessHandle::isAlive)
                .ifPresent(ProcessHandle::destroyForcibly);
    }

    private void joinReaders(List<Throwable> failures) {
        for (Thread reader : new Thread[]{outReader, errReader}) {
            try {
                reader.join(READER_JOIN_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
            }
            if (reader.isAlive()) {
                failures.add(new IOException("MINOS output reader thread did not terminate: " + reader.getName()));
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable, List<Throwable> failures) {
        try {
            closeable.close();
        } catch (IOException exception) {
            if (failures != null) failures.add(exception);
        }
    }

    private static void propagate(Throwable cause, List<Throwable> failures) throws MinosProtocolException {
        if (cause instanceof ProcessCanceledException canceled) {
            failures.forEach(canceled::addSuppressed);
            throw canceled;
        }
        if (cause != null) {
            MinosProtocolException wrapped = new MinosProtocolException(
                    "MINOS process stopped: " + cause.getMessage(), cause);
            failures.forEach(wrapped::addSuppressed);
            throw wrapped;
        }
        if (!failures.isEmpty()) {
            MinosProtocolException cleanupFailure = new MinosProtocolException(
                    "MINOS process cleanup did not complete cleanly", failures.getFirst());
            failures.stream().skip(1).forEach(cleanupFailure::addSuppressed);
            throw cleanupFailure;
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

    private record ProcessIdentity(long pid, Optional<Instant> startInstant) {
        private ProcessIdentity {
            startInstant = Objects.requireNonNull(startInstant, "startInstant");
        }
    }
}
