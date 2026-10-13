package com.minos.program.analysis;

import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Remembers, per project and snapshot, the cache key obtained by hashing the Java sources, for a bounded delay.
 *
 * <p>Without an exact snapshot fingerprint the only proof that a cached graph still matches the files is a hash of
 * every source. That hash is computed once, then reused until the delay elapses on a monotonic clock: a call inside
 * the delay reads no source, the first call after it reads them all again. Only a key that actually covers sources
 * is remembered. The memo holds one entry per project and at most {@link #MAX_ENTRIES} entries; each entry is a
 * snapshot identifier and a fixed-size digest.</p>
 */
final class SourceStateKeyMemo {

    static final int MAX_ENTRIES = 64;
    private static final int LOCK_STRIPES = 16;

    /** Full computation of the key: discovers and hashes the sources. */
    @FunctionalInterface
    interface Computation {
        JavaProgramGraphEngine.SourceStateKey compute() throws IOException;
    }

    private final long reuseNanos;
    private final LongSupplier monotonicNanoClock;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock[] computeLocks = new ReentrantLock[LOCK_STRIPES];

    SourceStateKeyMemo(Duration reuseDelay, LongSupplier monotonicNanoClock) {
        Objects.requireNonNull(reuseDelay, "reuseDelay");
        if (reuseDelay.isNegative() || reuseDelay.isZero()) {
            throw new IllegalArgumentException("reuseDelay must be greater than zero");
        }
        this.reuseNanos = reuseDelay.toNanos();
        this.monotonicNanoClock = Objects.requireNonNull(monotonicNanoClock, "monotonicNanoClock");
        for (int index = 0; index < computeLocks.length; index++) computeLocks[index] = new ReentrantLock();
    }

    /**
     * Returns the remembered key while it is younger than the delay, otherwise computes and remembers a new one.
     * Concurrent callers of one project share a single computation instead of hashing the sources once each.
     */
    JavaProgramGraphEngine.SourceStateKey resolve(UUID projectId, String snapshotId, Computation computation)
            throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(computation, "computation");
        Optional<JavaProgramGraphEngine.SourceStateKey> remembered = reusable(projectId, snapshotId);
        if (remembered.isPresent()) return remembered.orElseThrow();

        ReentrantLock lock = computeLocks[Math.floorMod(projectId.hashCode(), computeLocks.length)];
        lock.lock();
        try {
            remembered = reusable(projectId, snapshotId);
            if (remembered.isPresent()) return remembered.orElseThrow();
            JavaProgramGraphEngine.SourceStateKey computed =
                    Objects.requireNonNull(computation.compute(), "source state key");
            remember(projectId, snapshotId, computed);
            return computed;
        } finally {
            lock.unlock();
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private Optional<JavaProgramGraphEngine.SourceStateKey> reusable(UUID projectId, String snapshotId) {
        synchronized (entries) {
            Entry entry = entries.get(projectId);
            if (entry == null || !entry.snapshotId().equals(snapshotId)) return Optional.empty();
            long age = monotonicNanoClock.getAsLong() - entry.storedAtNanos();
            return age >= 0L && age < reuseNanos ? Optional.of(entry.key()) : Optional.empty();
        }
    }

    private void remember(UUID projectId, String snapshotId, JavaProgramGraphEngine.SourceStateKey key) {
        synchronized (entries) {
            if (!key.readsSources()) {
                entries.remove(projectId);
                return;
            }
            entries.put(projectId, new Entry(snapshotId, key, monotonicNanoClock.getAsLong()));
            Iterator<UUID> eldest = entries.keySet().iterator();
            while (entries.size() > MAX_ENTRIES && eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
    }

    private record Entry(String snapshotId, JavaProgramGraphEngine.SourceStateKey key, long storedAtNanos) { }
}
