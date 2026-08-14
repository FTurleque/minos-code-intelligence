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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the full lifecycle of one MINOS CLI process: start, output drain, cancellation, and
 * cleanup of every process that was observed as owned by the command.
 *
 * <p>Ownership is recorded continuously while the CLI root is alive, rather than only when a stop
 * begins. A descendant that is observed and later orphaned therefore remains in the ownership
 * ledger after the root exits. Entries include process start identity when the OS exposes it, so a
 * recycled PID cannot silently turn an old ownership record into permission to kill another
 * process.</p>
 *
 * <p>Cancellation and timeout deliberately keep the CLI root alive while known descendants are
 * signalled first. The forced phase delegates to IntelliJ's OS-aware
 * {@link OSProcessUtil#killProcessTree(Process)} while the root is alive and retains the ownership
 * ledger plus fresh ProcessHandle sweeps as fallback and verification layers. Cleanup is fail-closed:
 * close never converts an unproven cleanup into a successful command.</p>
 */
final class MinosProcessSupervisor implements AutoCloseable {

    private static final int MAX_PROCESS_STREAM_BYTES = 16 * 1024 * 1024;
    private static final int DESCENDANT_SWEEP_COUNT = 3;
    private static final long DESCENDANT_SWEEP_DELAY_MILLIS = 100L;
    private static final long OWNERSHIP_POLL_MILLIS = 20L;
    private static final long OWNERSHIP_JOIN_MILLIS = 1_000L;
    private static final long GRACEFUL_DESCENDANT_WAIT_MILLIS = 1_000L;
    private static final long FORCED_WAIT_MILLIS = 5_000L;
    private static final long READER_JOIN_MILLIS = 5_000L;

    private final Process process;
    private final Thread outReader;
    private final Thread errReader;
    private final Thread ownershipWatcher;
    private final AtomicReference<byte[]> stdout = new AtomicReference<>(new byte[0]);
    private final AtomicReference<byte[]> stderr = new AtomicReference<>(new byte[0]);
    private final AtomicReference<IOException> readFailure = new AtomicReference<>();
    private final AtomicBoolean terminationComplete = new AtomicBoolean(false);
    private final AtomicBoolean stopOwnershipWatcher = new AtomicBoolean(false);
    private final Map<ProcessIdentity, OwnedProcess> ownedProcesses = new LinkedHashMap<>();

    MinosProcessSupervisor(Process process) {
        this.process = process;
        remember(process.descendants().toList());
        this.outReader = Thread.ofVirtual().name("minos-stdout")
                .start(() -> readBounded(process.getInputStream(), stdout, readFailure));
        this.errReader = Thread.ofVirtual().name("minos-stderr")
                .start(() -> readBounded(process.getErrorStream(), stderr, readFailure));
        this.ownershipWatcher = Thread.ofVirtual().name("minos-process-ownership")
                .start(this::watchOwnership);
    }

    /** Waits up to {@code millis} for the process to exit. */
    boolean waitFor(long millis) throws InterruptedException {
        return process.waitFor(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the complete observed MINOS process tree. Cancellation is rethrown unchanged. A null
     * cause is the timeout/explicit-cleanup path; any cleanup failure is then surfaced as a protocol
     * error.
     */
    synchronized void stop(Throwable cause) throws MinosProtocolException {
        if (terminationComplete.get()) {
            if (cause instanceof ProcessCanceledException canceled) throw canceled;
            if (cause != null) throw new MinosProtocolException("MINOS process stopped: " + cause.getMessage(), cause);
            return;
        }

        List<Throwable> failures = new ArrayList<>();
        remember(process.descendants().toList());
        List<OwnedProcess> snapshot = rememberedProcesses();

        terminateDescendantsGracefully(snapshot);
        waitForDescendantsToSettle(GRACEFUL_DESCENDANT_WAIT_MILLIS, failures);
        remember(process.descendants().toList());

        if (process.isAlive()) {
            boolean platformTreeKilled = false;
            try {
                platformTreeKilled = OSProcessUtil.killProcessTree(process);
            } catch (RuntimeException exception) {
                failures.add(new IOException("IntelliJ platform process-tree termination failed", exception));
            }
            if (!platformTreeKilled && process.isAlive()) {
                failures.add(new IOException("IntelliJ platform process-tree termination returned false"));
            }
        }

        terminateDescendantsForcibly(rememberedProcesses(), failures);
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(FORCED_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }

        remember(process.descendants().toList());
        terminateRememberedSurvivors();
        long survivors = rememberedProcesses().stream().filter(OwnedProcess::isAlive).count();
        if (process.isAlive() || survivors > 0) {
            failures.add(new IOException(
                    "MINOS process tree has " + (process.isAlive() ? 1 : 0) + " root + "
                            + survivors + " owned descendant(s) still alive after forced termination"));
        }

        stopOwnershipWatcher(failures);
        // The reader threads own these streams and may already have closed them after EOF. Repeated
        // pipe close is teardown hygiene, not proof of process ownership, so it must remain idempotent.
        closeQuietly(process.getInputStream(), null);
        closeQuietly(process.getErrorStream(), null);
        joinReaders(failures);

        boolean readersStopped = !outReader.isAlive() && !errReader.isAlive();
        boolean ownershipStopped = !ownershipWatcher.isAlive();
        boolean processesStopped = !process.isAlive()
                && rememberedProcesses().stream().noneMatch(OwnedProcess::isAlive);
        terminationComplete.set(processesStopped && readersStopped && ownershipStopped);
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
        if (terminationComplete.get()) return;
        stop(null);
    }

    private void watchOwnership() {
        while (!stopOwnershipWatcher.get()) {
            remember(process.descendants().toList());
            if (!process.isAlive()) {
                remember(process.descendants().toList());
                return;
            }
            try {
                Thread.sleep(OWNERSHIP_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                if (stopOwnershipWatcher.get()) return;
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void stopOwnershipWatcher(List<Throwable> failures) {
        stopOwnershipWatcher.set(true);
        ownershipWatcher.interrupt();
        try {
            ownershipWatcher.join(OWNERSHIP_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        if (ownershipWatcher.isAlive()) {
            failures.add(new IOException("MINOS process ownership watcher did not terminate"));
        }
    }

    private synchronized void remember(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            ProcessIdentity identity = ProcessIdentity.capture(handle);
            ownedProcesses.putIfAbsent(identity, new OwnedProcess(identity, handle));
        }
    }

    private synchronized List<OwnedProcess> rememberedProcesses() {
        return new ArrayList<>(ownedProcesses.values());
    }

    private static void terminateDescendantsGracefully(List<OwnedProcess> snapshot) {
        List<OwnedProcess> processes = new ArrayList<>(snapshot);
        processes.reversed().forEach(OwnedProcess::destroy);
    }

    private void waitForDescendantsToSettle(long maximumMillis, List<Throwable> failures) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumMillis);
        while (System.nanoTime() < deadline) {
            remember(process.descendants().toList());
            if (rememberedProcesses().stream().noneMatch(OwnedProcess::isAlive)) return;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
                return;
            }
        }
    }

    private void terminateDescendantsForcibly(List<OwnedProcess> snapshot, List<Throwable> failures) {
        List<OwnedProcess> snapshotList = new ArrayList<>(snapshot);
        snapshotList.reversed().forEach(OwnedProcess::destroyForcibly);
        for (int sweep = 0; sweep < DESCENDANT_SWEEP_COUNT; sweep++) {
            List<ProcessHandle> fresh = new ArrayList<>(process.descendants().toList());
            remember(fresh);
            fresh.reversed().forEach(handle -> new OwnedProcess(ProcessIdentity.capture(handle), handle).destroyForcibly());
            terminateRememberedSurvivors();
            if (sweep < DESCENDANT_SWEEP_COUNT - 1
                    && (process.isAlive() || rememberedProcesses().stream().anyMatch(OwnedProcess::isAlive))) {
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
        List<OwnedProcess> processes = rememberedProcesses();
        processes.reversed().forEach(OwnedProcess::destroyForcibly);
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

        private static ProcessIdentity capture(ProcessHandle handle) {
            return new ProcessIdentity(handle.pid(), handle.info().startInstant());
        }
    }

    private record OwnedProcess(ProcessIdentity identity, ProcessHandle handle) {
        private OwnedProcess {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(handle, "handle");
        }

        private boolean isAlive() {
            if (!handle.isAlive()) return false;
            Optional<Instant> currentStart = handle.info().startInstant();
            return identity.startInstant().isEmpty()
                    || currentStart.isEmpty()
                    || identity.startInstant().equals(currentStart);
        }

        private void destroy() {
            if (isAlive()) handle.destroy();
        }

        private void destroyForcibly() {
            if (isAlive()) handle.destroyForcibly();
        }
    }
}
