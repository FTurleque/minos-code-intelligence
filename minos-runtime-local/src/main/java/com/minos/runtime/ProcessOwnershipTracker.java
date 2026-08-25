package com.minos.runtime;

import java.io.IOException;
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
 * Continuously records descendants of a provider process so they remain owned after re-parenting.
 *
 * <p>A remembered process is re-acquired by PID only when its start instant is available and
 * still matches. When the JVM/OS cannot provide a start instant, cleanup uses the originally
 * observed {@link ProcessHandle} only; a bare PID is never treated as sufficient authority to
 * terminate a process.</p>
 *
 * <p>The MINOS host JVM is an explicit non-ownable process. This invariant is enforced when a
 * handle is observed, when it is resolved again, and immediately before termination.</p>
 */
final class ProcessOwnershipTracker implements AutoCloseable {
    private static final long OWNERSHIP_POLL_MILLIS = 10L;
    private static final long POST_ROOT_EXIT_TRACK_MILLIS = 250L;
    private static final long GRACEFUL_WAIT_MILLIS = 1_000L;
    private static final long FORCED_WAIT_MILLIS = 5_000L;
    private static final long TRACKER_JOIN_MILLIS = 2_000L;

    private final Process process;
    private final long hostPid = ProcessTreeTermination.hostPid();
    private final Object lifecycleLock = new Object();
    private final Object ownershipLock = new Object();
    private final Map<ProcessIdentity, OwnedProcess> owned = new LinkedHashMap<>();
    private final AtomicBoolean trackerStop = new AtomicBoolean(false);
    private final AtomicReference<IOException> trackerFailure = new AtomicReference<>();
    private final Thread tracker;
    private boolean terminated;

    ProcessOwnershipTracker(Process process) {
        this.process = Objects.requireNonNull(process, "process");
        remember(process.descendants().toList());
        tracker = Thread.ofVirtual().name("minos-provider-process-ownership").start(this::trackOwnership);
    }

    void terminate() throws IOException {
        synchronized (lifecycleLock) {
            if (terminated) return;

            List<Throwable> failures = new ArrayList<>();
            remember(process.descendants().toList());
            destroyRemembered(false);
            if (process.isAlive()) process.destroy();
            waitForOwnedToSettle(GRACEFUL_WAIT_MILLIS, failures);

            remember(process.descendants().toList());
            destroyRemembered(true);
            if (process.isAlive()) process.destroyForcibly();
            waitForRoot(FORCED_WAIT_MILLIS, failures);

            stopTracker(failures);
            remember(process.descendants().toList());
            destroyRemembered(true);
            waitForOwnedToSettle(FORCED_WAIT_MILLIS, failures);

            long survivors = snapshotOwned().stream().filter(this::isSameOwnedProcessAlive).count();
            if (process.isAlive() || survivors > 0) {
                failures.add(new IOException(
                        "provider process tree has " + (process.isAlive() ? 1 : 0) + " root + "
                                + survivors + " owned descendant(s) still alive after forced termination"));
            }
            IOException tracking = trackerFailure.get();
            if (tracking != null) failures.add(tracking);

            terminated = !process.isAlive() && survivors == 0 && !tracker.isAlive();
            if (!failures.isEmpty()) {
                IOException failure = new IOException("provider process cleanup did not complete cleanly", failures.getFirst());
                failures.stream().skip(1).forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    @Override
    public void close() throws IOException {
        terminate();
    }

    private void trackOwnership() {
        long rootExitObservedAt = -1L;
        try {
            while (!trackerStop.get()) {
                remember(process.descendants().toList());
                if (!process.isAlive()) {
                    if (rootExitObservedAt < 0L) rootExitObservedAt = System.nanoTime();
                    if (System.nanoTime() - rootExitObservedAt
                            >= TimeUnit.MILLISECONDS.toNanos(POST_ROOT_EXIT_TRACK_MILLIS)) {
                        return;
                    }
                }
                Thread.sleep(OWNERSHIP_POLL_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            if (!trackerStop.get()) {
                Thread.currentThread().interrupt();
                trackerFailure.compareAndSet(null,
                        new IOException("provider process ownership tracking was interrupted", interrupted));
            }
        } catch (RuntimeException failure) {
            trackerFailure.compareAndSet(null,
                    new IOException("provider process ownership tracking failed", failure));
        }
    }

    private void stopTracker(List<Throwable> failures) {
        trackerStop.set(true);
        tracker.interrupt();
        try {
            tracker.join(TRACKER_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        if (tracker.isAlive()) failures.add(new IOException("provider process ownership tracker did not terminate"));
    }

    /** Package-visible so containment tests can inject hostile/invalid observations deterministically. */
    void remember(List<ProcessHandle> handles) {
        synchronized (ownershipLock) {
            for (ProcessHandle handle : handles) {
                if (handle.pid() == hostPid) continue;
                ProcessIdentity identity = identity(handle);
                owned.putIfAbsent(identity, new OwnedProcess(handle, identity));
            }
        }
    }

    boolean ownsPid(long pid) {
        synchronized (ownershipLock) {
            return owned.keySet().stream().anyMatch(identity -> identity.pid() == pid);
        }
    }

    private List<OwnedProcess> snapshotOwned() {
        synchronized (ownershipLock) {
            return new ArrayList<>(owned.values());
        }
    }

    private void destroyRemembered(boolean forcibly) {
        List<OwnedProcess> snapshot = snapshotOwned();
        snapshot.reversed().forEach(ownedProcess -> resolveSameProcess(ownedProcess)
                .filter(ProcessHandle::isAlive)
                .ifPresent(handle -> destroyIfOwned(handle, forcibly)));
    }

    private void destroyIfOwned(ProcessHandle handle, boolean forcibly) {
        // Defence in depth against corrupted bookkeeping, PID reuse and platform-specific tree
        // enumeration anomalies: the host JVM is never a valid provider-owned process. The check
        // lives in the shared policy so this tracker and the process executor cannot diverge.
        ProcessTreeTermination.destroyIfNotHost(handle, forcibly);
    }

    private void waitForOwnedToSettle(long maximumMillis, List<Throwable> failures) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumMillis);
        while (System.nanoTime() < deadline) {
            remember(process.descendants().toList());
            if (snapshotOwned().stream().noneMatch(this::isSameOwnedProcessAlive)) return;
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
                return;
            }
        }
    }

    private void waitForRoot(long maximumMillis, List<Throwable> failures) {
        if (!process.isAlive()) return;
        try {
            process.waitFor(maximumMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
    }

    private boolean isSameOwnedProcessAlive(OwnedProcess ownedProcess) {
        return resolveSameProcess(ownedProcess).map(ProcessHandle::isAlive).orElse(false);
    }

    private Optional<ProcessHandle> resolveSameProcess(OwnedProcess ownedProcess) {
        ProcessIdentity expected = ownedProcess.identity();
        if (expected.pid() == hostPid) return Optional.empty();
        if (expected.startInstant().isEmpty()) {
            ProcessHandle original = ownedProcess.handle();
            return original.pid() != hostPid && original.isAlive() ? Optional.of(original) : Optional.empty();
        }
        return ProcessHandle.of(expected.pid())
                .filter(current -> current.pid() != hostPid)
                .filter(current -> current.info().startInstant().equals(expected.startInstant()));
    }

    private static ProcessIdentity identity(ProcessHandle handle) {
        return new ProcessIdentity(handle.pid(), handle.info().startInstant());
    }

    private record OwnedProcess(ProcessHandle handle, ProcessIdentity identity) {
        private OwnedProcess {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(identity, "identity");
        }
    }

    private record ProcessIdentity(long pid, Optional<Instant> startInstant) {
        private ProcessIdentity {
            startInstant = Objects.requireNonNull(startInstant, "startInstant");
        }
    }
}
