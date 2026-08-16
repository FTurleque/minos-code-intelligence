package com.minos.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Shared fallback policy for tearing down a provider process tree.
 *
 * <p>The kernel boundary is the authority: a Linux cgroup scope or a Windows Job Object is what
 * actually guarantees containment, and it is destroyed first by the caller. What remains is defence
 * in depth over the handles MINOS itself observed, and it is centralised here so the runtime
 * executor and the ownership tracker cannot drift into two different fallback policies.</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>The host JVM is never a provider-owned process. Its PID is excluded from every destructive
 *       operation and re-checked immediately before the destroy call, because tree enumeration can
 *       surface anomalies under PID reuse and on platforms with less precise parent tracking.</li>
 *   <li>Termination is graceful, then bounded, then forced -- never an unbounded wait.</li>
 *   <li>Descendants are destroyed deepest-first so a parent cannot respawn a child that was
 *       already asked to stop.</li>
 *   <li>Only handles reachable from the supplied root are touched. There is no global process
 *       scan, so two concurrent jobs can never terminate one another.</li>
 * </ul>
 */
final class ProcessTreeTermination {

    private static final long HOST_PID = ProcessHandle.current().pid();
    private static final long SETTLE_POLL_MILLIS = 25L;

    private ProcessTreeTermination() {
    }

    /** True when the handle denotes this JVM, which is never a provider-owned process. */
    static boolean isHostProcess(ProcessHandle handle) {
        return Objects.requireNonNull(handle, "handle").pid() == HOST_PID;
    }

    static long hostPid() {
        return HOST_PID;
    }

    /**
     * Destroys {@code handle} unless it is the host JVM. The check happens here, immediately before
     * the destroy, so a caller cannot bypass it by holding a stale decision.
     */
    static void destroyIfNotHost(ProcessHandle handle, boolean forcibly) {
        if (isHostProcess(handle)) return;
        if (forcibly) handle.destroyForcibly();
        else handle.destroy();
    }

    /**
     * Terminates {@code root} and everything reachable below it: graceful request, bounded wait,
     * then a forced sweep that also covers descendants which appeared during the grace period.
     *
     * @return {@code true} when the root and every observed descendant are dead
     */
    static boolean terminateTree(Process root, Duration gracefulWait, Duration forcedWait) {
        Objects.requireNonNull(root, "root");
        List<ProcessHandle> observed = new ArrayList<>(root.descendants().toList());

        destroyDeepestFirst(observed, false);
        if (root.isAlive()) root.destroy();
        awaitSettled(root, observed, gracefulWait);

        // Re-enumerate: the grace period is exactly when a lingering parent may have spawned more.
        List<ProcessHandle> remaining = new ArrayList<>(root.descendants().toList());
        for (ProcessHandle handle : remaining) {
            if (!containsHandle(observed, handle)) observed.add(handle);
        }
        destroyDeepestFirst(observed, true);
        if (root.isAlive()) root.destroyForcibly();
        awaitSettled(root, observed, forcedWait);

        return !root.isAlive() && observed.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static void destroyDeepestFirst(List<ProcessHandle> handles, boolean forcibly) {
        handles.reversed().forEach(handle -> {
            if (handle.isAlive()) destroyIfNotHost(handle, forcibly);
        });
    }

    private static void awaitSettled(Process root, List<ProcessHandle> handles, Duration wait) {
        long budgetMillis = Math.max(0L, wait.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        while (System.nanoTime() < deadline) {
            if (!root.isAlive() && handles.stream().noneMatch(ProcessHandle::isAlive)) return;
            try {
                Thread.sleep(SETTLE_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean containsHandle(List<ProcessHandle> handles, ProcessHandle candidate) {
        return handles.stream().anyMatch(handle -> handle.equals(candidate));
    }
}
