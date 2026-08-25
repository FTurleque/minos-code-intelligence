package com.minos.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reference-counted, cross-process leases over the entries of a shared on-disk cache.
 *
 * <p>A cache entry is protected by a {@code <key>.lease} file. {@link #acquire(String)} pins the
 * entry for the calling JVM and every concurrent process; the pin is released by a matching
 * {@link #release(String)}. Within one JVM the lease is reference counted, because a file lock is
 * held per channel and a second {@code tryLock} on the same file from the same JVM would raise
 * {@link OverlappingFileLockException} rather than block.</p>
 *
 * <p>Acquisition is bounded: the JVM stripe and the OS file lock share a single
 * {@link LeaseDeadline}, so a caller waits at most the configured timeout no matter how the wait
 * splits between the two layers. An entry that is pinned anywhere is never evictable, which is why
 * {@link #tryAcquireEviction(String)} never waits at all -- an eviction that blocked on a live
 * reader would convert cache pressure into a stall.</p>
 */
public final class SharedCacheLeaseRegistry {

    private static final int STRIPE_COUNT = 64;

    private final Path leasesRoot;
    private final Duration acquireTimeout;
    private final String description;
    private final Object monitor = new Object();
    private final ReentrantLock[] stripes = createStripes();
    private final Map<String, LeaseState> active = new HashMap<>();

    public SharedCacheLeaseRegistry(Path leasesRoot, Duration acquireTimeout, String description) {
        this.leasesRoot = Objects.requireNonNull(leasesRoot, "leasesRoot").toAbsolutePath().normalize();
        Duration timeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout must be positive");
        }
        this.acquireTimeout = timeout;
        this.description = requireText(description);
    }

    /** Pins {@code cacheKey} for this JVM and every other process, or fails within the timeout. */
    public void acquire(String cacheKey) throws IOException {
        String key = requireText(cacheKey);
        LeaseDeadline deadline = LeaseDeadline.after(acquireTimeout);
        ReentrantLock stripe = stripe(key);
        boolean stripeAcquired = false;
        try {
            stripeAcquired = stripe.tryLock(deadline.remainingNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (!stripeAcquired) throw deadline.timeout(description + " JVM lease " + key);
            synchronized (monitor) {
                LeaseState existing = active.get(key);
                if (existing != null) {
                    existing.references++;
                    return;
                }
            }
            LeaseState created = openLease(key, deadline);
            synchronized (monitor) {
                active.put(key, created);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + description + " JVM lease " + key, interrupted);
        } finally {
            if (stripeAcquired) stripe.unlock();
        }
    }

    /**
     * Drops one reference; the OS lock is released only when the last one goes. Releasing a key
     * that is not held is a no-op so a cleanup path can run unconditionally.
     */
    public void release(String cacheKey) throws IOException {
        String key = requireText(cacheKey);
        synchronized (monitor) {
            LeaseState state = active.get(key);
            if (state == null) return;
            state.references--;
            if (state.references > 0) return;
            active.remove(key);
            IOException failure = releaseQuietly(state.lock);
            failure = combine(failure, closeQuietly(state.channel));
            if (failure != null) throw failure;
        }
    }

    /** True when this JVM currently pins {@code cacheKey}. */
    public boolean isHeld(String cacheKey) {
        synchronized (monitor) {
            return active.containsKey(requireText(cacheKey));
        }
    }

    /**
     * Takes an exclusive, non-waiting lease used to evict an entry, or {@code null} when the entry
     * is pinned by this JVM or another process. Never blocks.
     */
    public EvictionLease tryAcquireEviction(String cacheKey) throws IOException {
        String key = requireText(cacheKey);
        synchronized (monitor) {
            if (active.containsKey(key)) return null;
        }
        FileChannel channel = openLeaseChannel(key);
        try {
            FileLock lock = tryFileLock(channel);
            if (lock == null) {
                channel.close();
                return null;
            }
            return new EvictionLease(channel, lock);
        } catch (IOException | RuntimeException failure) {
            IOException cleanupFailure = closeQuietly(channel);
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }

    private LeaseState openLease(String cacheKey, LeaseDeadline deadline) throws IOException {
        FileChannel channel = openLeaseChannel(cacheKey);
        try {
            FileLock lock = tryFileLock(channel);
            while (lock == null) {
                deadline.pauseBeforeRetry(description + " cross-process lease " + cacheKey);
                lock = tryFileLock(channel);
            }
            return new LeaseState(channel, lock);
        } catch (IOException | RuntimeException failure) {
            IOException cleanupFailure = closeQuietly(channel);
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }

    private FileChannel openLeaseChannel(String cacheKey) throws IOException {
        Path leaseFile = leasesRoot.resolve(cacheKey + ".lease").normalize();
        if (!leasesRoot.equals(leaseFile.getParent())) {
            throw new IOException(description + " lease key escapes the lease directory: " + cacheKey);
        }
        return FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private static FileLock tryFileLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException heldThroughAnotherChannelInThisJvm) {
            return null;
        }
    }

    private ReentrantLock stripe(String cacheKey) {
        return stripes[Math.floorMod(cacheKey.hashCode(), stripes.length)];
    }

    private static ReentrantLock[] createStripes() {
        ReentrantLock[] created = new ReentrantLock[STRIPE_COUNT];
        for (int index = 0; index < created.length; index++) created[index] = new ReentrantLock();
        return created;
    }

    private static IOException releaseQuietly(FileLock lock) {
        try {
            lock.release();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    private static IOException closeQuietly(FileChannel channel) {
        if (channel == null) return null;
        try {
            channel.close();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    private static IOException combine(IOException current, IOException additional) {
        if (additional == null) return current;
        if (current == null) return additional;
        current.addSuppressed(additional);
        return current;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("lease key must not be blank");
        return value;
    }

    private static final class LeaseState {
        private final FileChannel channel;
        private final FileLock lock;
        private int references = 1;

        private LeaseState(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }

    /** Exclusive hold on an unpinned entry for the duration of its eviction. */
    public static final class EvictionLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private EvictionLease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = releaseQuietly(lock);
            failure = combine(failure, closeQuietly(channel));
            if (failure != null) throw failure;
        }
    }
}
