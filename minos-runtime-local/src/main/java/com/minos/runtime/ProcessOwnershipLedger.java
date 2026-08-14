package com.minos.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuously records descendants of one provider process so an observed descendant remains owned
 * even if the provider root exits and the OS reparents that descendant before cleanup begins.
 *
 * <p>Qualified sandbox transformers remain the primary OS containment boundary. This ledger is the
 * provider-independent fallback and verification layer for trusted/uncontained plans and for cleanup
 * after the root process has already exited. Process identity includes the start instant when the OS
 * exposes it, preventing a recycled PID from being treated as an owned process.</p>
 */
final class ProcessOwnershipLedger implements AutoCloseable {

    private static final long POLL_MILLIS = 10L;
    private static final long WATCHER_JOIN_MILLIS = 1_000L;
    private static final long ROOT_WAIT_MILLIS = 5_000L;
    private static final int TERMINATION_SWEEPS = 4;
    private static final long SWEEP_DELAY_MILLIS = 50L;

    private final Process root;
    private final AtomicBoolean stopWatcher = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<ProcessIdentity, OwnedProcess> owned = new LinkedHashMap<>();
    private final Thread watcher;

    ProcessOwnershipLedger(Process root) {
        this.root = Objects.requireNonNull(root, "root");
        remember(root.descendants().toList());
        this.watcher = Thread.ofVirtual().name("minos-provider-process-ownership").start(this::watch);
    }

    /** Kills every observed descendant but leaves a still-running root to the caller/OS containment. */
    void terminateOwnedDescendants() {
        remember(root.descendants().toList());
        for (int sweep = 0; sweep < TERMINATION_SWEEPS; sweep++) {
            remember(root.descendants().toList());
            terminateRemembered();
            if (remembered().stream().noneMatch(OwnedProcess::isAlive)) return;
            sleepSweep();
        }
        verifyNoOwnedSurvivors();
    }

    /**
     * Kills every observed descendant and the root itself, then proves that observed ownership is
     * empty. A resistant descendant never prevents the root and the remaining ownership set from
     * receiving their own termination attempts before failure is surfaced.
     */
    void terminateAll() {
        RuntimeException preliminaryFailure = null;
        try {
            terminateOwnedDescendants();
        } catch (RuntimeException failure) {
            preliminaryFailure = failure;
        }

        if (root.isAlive()) root.destroyForcibly();
        try {
            root.waitFor(ROOT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException(
                    "provider process termination was interrupted", interrupted);
            if (preliminaryFailure != null) failure.addSuppressed(preliminaryFailure);
            preliminaryFailure = failure;
        }

        for (int sweep = 0; sweep < TERMINATION_SWEEPS; sweep++) {
            remember(root.descendants().toList());
            terminateRemembered();
            if (!root.isAlive() && remembered().stream().noneMatch(OwnedProcess::isAlive)) return;
            if (sweep < TERMINATION_SWEEPS - 1) {
                try {
                    sleepSweep();
                } catch (RuntimeException failure) {
                    if (preliminaryFailure == null) preliminaryFailure = failure;
                    else preliminaryFailure.addSuppressed(failure);
                    break;
                }
            }
        }

        IllegalStateException terminalFailure = new IllegalStateException(
                "provider process cleanup did not prove complete: rootAlive=" + root.isAlive()
                        + ", survivingDescendants=" + survivingOwnedCount());
        if (preliminaryFailure != null) terminalFailure.addSuppressed(preliminaryFailure);
        throw terminalFailure;
    }

    /** Callback-safe variant used by quota containment; explicit execution paths verify again. */
    void terminateAllQuietly() {
        try {
            terminateAll();
        } catch (RuntimeException ignored) {
            // The execution thread performs the authoritative cleanup/verification before returning.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException cleanupFailure = null;
        try {
            terminateAll();
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        } finally {
            stopWatcher.set(true);
            watcher.interrupt();
            try {
                watcher.join(WATCHER_JOIN_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (cleanupFailure == null) {
                    cleanupFailure = new IllegalStateException("provider ownership watcher join was interrupted", interrupted);
                } else {
                    cleanupFailure.addSuppressed(interrupted);
                }
            }
            if (watcher.isAlive()) {
                IllegalStateException watcherFailure = new IllegalStateException(
                        "provider ownership watcher did not terminate");
                if (cleanupFailure == null) cleanupFailure = watcherFailure;
                else cleanupFailure.addSuppressed(watcherFailure);
            }
        }
        if (cleanupFailure != null) {
            // Keep close retryable after an unproven cleanup; remembered ownership is retained.
            closed.set(false);
            throw cleanupFailure;
        }
    }

    private void watch() {
        while (!stopWatcher.get()) {
            remember(root.descendants().toList());
            if (!root.isAlive()) {
                // Final sample closes the normal-exit observation window as far as ProcessHandle permits.
                remember(root.descendants().toList());
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                if (stopWatcher.get()) return;
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void remember(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            ProcessIdentity identity = ProcessIdentity.capture(handle);
            owned.putIfAbsent(identity, new OwnedProcess(identity, handle));
        }
    }

    private synchronized List<OwnedProcess> remembered() {
        return new ArrayList<>(owned.values());
    }

    private void terminateRemembered() {
        List<OwnedProcess> snapshot = remembered();
        snapshot.reversed().forEach(OwnedProcess::destroyForcibly);
    }

    private long survivingOwnedCount() {
        return remembered().stream().filter(OwnedProcess::isAlive).count();
    }

    private void verifyNoOwnedSurvivors() {
        long survivors = survivingOwnedCount();
        if (survivors > 0L) {
            throw new IllegalStateException(
                    "provider process ownership contains " + survivors + " surviving descendant(s)");
        }
    }

    private static void sleepSweep() {
        try {
            Thread.sleep(SWEEP_DELAY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provider descendant cleanup was interrupted", interrupted);
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

        private void destroyForcibly() {
            if (isAlive()) handle.destroyForcibly();
        }
    }
}
