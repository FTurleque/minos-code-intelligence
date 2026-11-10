package com.minos.storage.codec;

import com.minos.diagnostics.SnapshotMemoryExhaustedException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The memory budget of the snapshot query views of one process: a fraction of the maximum heap, shared by the
 * views cached by every registered store and by the loads in progress.
 *
 * <p>A load of a view that is not cached reserves its estimated weight before it reads or decodes a byte of the
 * snapshot. A weight above the whole budget is refused at once. A weight that does not fit beside the cached views
 * and the other loads first evicts every cached view of the registered stores; if the loads in progress still hold
 * the budget, it waits for a release until the admission wait elapses, then is refused. Every refusal is a
 * {@link SnapshotMemoryExhaustedException}, never an {@link OutOfMemoryError}. When the view is cached, the
 * reservation is converted into resident weight under the budget lock, so the sum of the reservations and of the
 * cached weights never exceeds the budget.</p>
 *
 * <p>The fraction is read from the system property {@value #PROPERTY}, then from the variable {@value #VARIABLE},
 * {@value #DEFAULT_PERCENT} by default, never from the MINOS configuration file: the heap is a fact of the process.
 * A value that is not an integer from {@value #MINIMUM_PERCENT} to {@value #MAXIMUM_PERCENT} refuses every load; it
 * never falls back to the default.</p>
 *
 * <p>Lock order: the budget lock first, then the lock of a store's cache. A store never calls the budget while it
 * holds its own cache lock; {@link Tenant} methods and the publication passed to {@link #convert} run under the
 * budget lock and may take the store's cache lock.</p>
 */
public final class QueryViewMemoryBudget {

    /** System property that sets the fraction of the maximum heap given to the snapshot views, in percent. */
    public static final String PROPERTY = "minos.storage.viewHeapPercent";
    /** Environment variable read when the system property is absent. */
    public static final String VARIABLE = "MINOS_VIEW_HEAP_PERCENT";
    public static final int DEFAULT_PERCENT = 50;
    public static final int MINIMUM_PERCENT = 10;
    public static final int MAXIMUM_PERCENT = 80;

    private static final long PERCENT = 100L;
    private static final String SETTING_REFUSAL = VARIABLE + " (or -D" + PROPERTY + ") must be an integer from "
            + MINIMUM_PERCENT + " to " + MAXIMUM_PERCENT;

    private final long maximumHeapBytes;
    private final int percent;
    private final long budgetBytes;
    private final long admissionWaitNanos;
    private final String settingRefusal;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private final List<WeakReference<Tenant>> tenants = new ArrayList<>();
    private long reservedBytes;
    private long waits;
    private long refusals;

    private QueryViewMemoryBudget(long maximumHeapBytes, int percent, Duration admissionWait, String settingRefusal) {
        if (maximumHeapBytes < 1L) throw new IllegalArgumentException("maximumHeapBytes must be greater than zero");
        Objects.requireNonNull(admissionWait, "admissionWait");
        if (admissionWait.isNegative() || admissionWait.isZero()) {
            throw new IllegalArgumentException("admissionWait must be positive");
        }
        if (settingRefusal == null && (percent < MINIMUM_PERCENT || percent > MAXIMUM_PERCENT)) {
            throw new IllegalArgumentException(SETTING_REFUSAL);
        }
        this.maximumHeapBytes = maximumHeapBytes;
        this.percent = percent;
        this.budgetBytes = settingRefusal == null ? fraction(maximumHeapBytes, percent) : 0L;
        this.admissionWaitNanos = admissionWait.toNanos();
        this.settingRefusal = settingRefusal;
    }

    /** The budget of this process, resolved once from its maximum heap, its system properties and environment. */
    public static QueryViewMemoryBudget shared() {
        return Shared.INSTANCE;
    }

    /** A budget of {@code percent} of {@code maximumHeapBytes}; tests and measurements inject it into a store. */
    public static QueryViewMemoryBudget of(long maximumHeapBytes, int percent, Duration admissionWait) {
        return new QueryViewMemoryBudget(maximumHeapBytes, percent, admissionWait, null);
    }

    /**
     * The budget that the given settings describe: the system property first, then the variable, the default
     * otherwise. An invalid value gives a budget that refuses every reservation, naming the setting and its range.
     */
    public static QueryViewMemoryBudget resolve(
            long maximumHeapBytes, Map<String, String> environment, Properties properties, Duration admissionWait) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        String raw = properties.getProperty(PROPERTY);
        if (raw == null) raw = environment.get(VARIABLE);
        if (raw == null) return of(maximumHeapBytes, DEFAULT_PERCENT, admissionWait);
        Integer parsed = parsePercent(raw);
        if (parsed == null) return new QueryViewMemoryBudget(maximumHeapBytes, 0, admissionWait, SETTING_REFUSAL);
        return of(maximumHeapBytes, parsed, admissionWait);
    }

    /** The budget in bytes; 0 when the setting is invalid. */
    public long budgetBytes() {
        return budgetBytes;
    }

    /** The fraction of the maximum heap, in percent; 0 when the setting is invalid. */
    public int percent() {
        return percent;
    }

    public long maximumHeapBytes() {
        return maximumHeapBytes;
    }

    /**
     * The weight ceiling a store gives its view cache: the smaller of its configured ceiling and the budget. Under an
     * invalid setting every load is refused, so the configured ceiling is kept.
     */
    public long effectiveCacheCeiling(long configuredCeiling) {
        if (configuredCeiling < 1L) throw new IllegalArgumentException("configuredCeiling must be greater than zero");
        if (settingRefusal != null) return configuredCeiling;
        return Math.max(1L, Math.min(configuredCeiling, budgetBytes));
    }

    /** Registers a store whose cached views count against the budget and may be evicted; held weakly. */
    public void register(Tenant tenant) {
        Objects.requireNonNull(tenant, "tenant");
        lock.lock();
        try {
            tenants.add(new WeakReference<>(tenant));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves {@code weightBytes} for a view about to be loaded, waiting at most the admission wait.
     *
     * @throws SnapshotMemoryExhaustedException when the weight exceeds the budget, or does not fit before the wait elapses
     * @throws IOException when the setting of the budget is invalid
     */
    public Reservation reserve(long weightBytes) throws IOException {
        if (weightBytes < 0L) throw new IllegalArgumentException("weightBytes must not be negative");
        if (settingRefusal != null) throw new IOException(settingRefusal);
        lock.lock();
        try {
            if (weightBytes > budgetBytes) {
                refusals++;
                throw new SnapshotMemoryExhaustedException(SnapshotMemoryExhaustedException.MESSAGE_PREFIX
                        + ": its view needs an estimated " + weightBytes + " bytes and the view memory budget is "
                        + budgetBytes + " bytes (" + percent + " percent of the " + maximumHeapBytes
                        + "-byte maximum heap); raise the maximum heap (-Xmx or -XX:MaxRAMPercentage) or "
                        + VARIABLE);
            }
            long deadline = System.nanoTime() + admissionWaitNanos;
            boolean waited = false;
            boolean evicted = false;
            while (true) {
                if (weightBytes <= budgetBytes - reservedBytes - residentBytes()) {
                    reservedBytes += weightBytes;
                    return new Reservation(weightBytes);
                }
                if (!evicted) {
                    evictCachedViews();
                    evicted = true;
                    continue;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    refusals++;
                    throw new SnapshotMemoryExhaustedException(SnapshotMemoryExhaustedException.MESSAGE_PREFIX
                            + " now: " + weightBytes + " bytes could not be reserved within "
                            + describe(admissionWaitNanos) + " while other snapshot views use the " + budgetBytes
                            + "-byte budget; retry later, raise the maximum heap or " + VARIABLE);
                }
                if (!waited) {
                    waits++;
                    waited = true;
                }
                released.awaitNanos(remaining);
                // Views cached since the last eviction may now be all that stands in the way.
                evicted = false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("snapshot view admission was interrupted");
            failure.initCause(interrupted);
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code publication}, which puts the loaded view into a store's cache, and releases {@code reservation}
     * under the same lock: no reservation can slip between the release and the growth of the cached weight.
     */
    public <T> T convert(Reservation reservation, Supplier<T> publication) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(publication, "publication");
        lock.lock();
        try {
            return publication.get();
        } finally {
            try {
                reservation.releaseLocked();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Wakes the waiting reservations after a store dropped cached views by itself (invalidation). */
    public void signalReleased() {
        lock.lock();
        try {
            released.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Budget, reserved and resident weights and counters, for tests and measurements. */
    public Stats stats() {
        lock.lock();
        try {
            return new Stats(budgetBytes, reservedBytes, residentBytes(), waits, refusals);
        } finally {
            lock.unlock();
        }
    }

    private long residentBytes() {
        long total = 0L;
        Iterator<WeakReference<Tenant>> iterator = tenants.iterator();
        while (iterator.hasNext()) {
            Tenant tenant = iterator.next().get();
            if (tenant == null) {
                iterator.remove();
                continue;
            }
            long weight = Math.max(0L, tenant.residentWeightBytes());
            total = weight > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + weight;
        }
        return total;
    }

    private void evictCachedViews() {
        for (WeakReference<Tenant> reference : List.copyOf(tenants)) {
            Tenant tenant = reference.get();
            if (tenant != null) tenant.evictAll();
        }
    }

    private static long fraction(long bytes, int percent) {
        return (bytes / PERCENT) * percent + (bytes % PERCENT) * percent / PERCENT;
    }

    private static Integer parsePercent(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 3) return null;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return null;
        }
        int parsed = Integer.parseInt(value);
        return parsed < MINIMUM_PERCENT || parsed > MAXIMUM_PERCENT ? null : parsed;
    }

    private static String describe(long nanos) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(nanos);
        return seconds >= 1L && TimeUnit.SECONDS.toNanos(seconds) == nanos
                ? seconds + " seconds"
                : TimeUnit.NANOSECONDS.toMillis(nanos) + " milliseconds";
    }

    /** A store whose cached views count against the budget. */
    public interface Tenant {
        /** Total estimated weight of the views it caches now. */
        long residentWeightBytes();

        /** Drops every view it caches; a view still used by a caller stays reachable until that caller is done. */
        void evictAll();
    }

    /** Weight held for one load; closing it releases what a conversion did not already release. */
    public final class Reservation implements AutoCloseable {
        private final long bytes;
        private boolean released;

        private Reservation(long bytes) {
            this.bytes = bytes;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            lock.lock();
            try {
                releaseLocked();
            } finally {
                lock.unlock();
            }
        }

        private void releaseLocked() {
            if (released) return;
            released = true;
            reservedBytes -= bytes;
            QueryViewMemoryBudget.this.released.signalAll();
        }
    }

    /** A snapshot of the budget's accounting. */
    public record Stats(long budgetBytes, long reservedBytes, long residentBytes, long waits, long refusals) { }

    private static final class Shared {
        private static final QueryViewMemoryBudget INSTANCE = resolve(Runtime.getRuntime().maxMemory(),
                System.getenv(), System.getProperties(), SnapshotLimits.VIEW_ADMISSION_WAIT);
    }
}
