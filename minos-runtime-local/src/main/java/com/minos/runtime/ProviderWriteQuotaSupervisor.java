package com.minos.runtime;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enforces {@link ProviderWriteQuota} on every root an untrusted provider can write to.
 *
 * <p>The supervisor samples the writable roots at a bounded period and destroys the OS job boundary
 * the moment the aggregate byte or entry budget is crossed. Each sample is itself bounded: the walk
 * stops as soon as the budget is exceeded, so a pathological tree can never make the supervisor
 * unbounded work. Symbolic links are never followed, so a provider cannot make the supervisor
 * traverse outside its own roots.</p>
 */
final class ProviderWriteQuotaSupervisor implements AutoCloseable {

    /** Idle periods per sampling period, so supervision never burns more than 1/(1+N) of a core. */
    private static final long IDLE_RATIO = 3L;

    private final List<Path> roots;
    private final ProviderWriteQuota quota;
    private final Runnable jobKill;
    private final Thread thread;
    private final AtomicReference<String> breach = new AtomicReference<>();
    private final AtomicLong observedBytes = new AtomicLong();
    private final AtomicLong observedEntries = new AtomicLong();
    private volatile boolean running = true;

    private ProviderWriteQuotaSupervisor(List<Path> roots, ProviderWriteQuota quota, Runnable jobKill) {
        this.roots = roots;
        this.quota = quota;
        this.jobKill = jobKill;
        this.thread = new Thread(this::supervise, "minos-provider-write-quota");
        this.thread.setDaemon(true);
    }

    /**
     * Starts supervising the given roots.
     *
     * @param jobKill destroys the whole OS job boundary; invoked at most once, on breach
     */
    static ProviderWriteQuotaSupervisor start(
            Set<Path> writableRoots,
            ProviderWriteQuota quota,
            Runnable jobKill
    ) {
        Objects.requireNonNull(writableRoots, "writableRoots");
        Objects.requireNonNull(quota, "quota");
        Objects.requireNonNull(jobKill, "jobKill");
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path root : writableRoots) {
            if (root == null) continue;
            Path candidate = root.toAbsolutePath().normalize();
            if (normalized.stream().anyMatch(candidate::startsWith)) continue;
            normalized.removeIf(existing -> existing.startsWith(candidate));
            normalized.add(candidate);
        }
        ProviderWriteQuotaSupervisor supervisor =
                new ProviderWriteQuotaSupervisor(List.copyOf(normalized), quota, jobKill);
        supervisor.thread.start();
        return supervisor;
    }

    /** Returns the breach description when the provider crossed its write budget. */
    Optional<String> breach() {
        return Optional.ofNullable(breach.get());
    }

    long observedBytes() {
        return observedBytes.get();
    }

    long observedEntries() {
        return observedEntries.get();
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(quota.samplePeriod().toMillis() + 5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void supervise() {
        while (running) {
            long startedAt = System.nanoTime();
            Sample sample = sample();
            long elapsedMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            observedBytes.set(sample.bytes());
            observedEntries.set(sample.entries());
            if (sample.bytes() > quota.maxBytes() || sample.entries() > quota.maxEntries()) {
                breach.compareAndSet(null, "provider exceeded its write quota: bytes=" + sample.bytes()
                        + "/" + quota.maxBytes() + ", entries=" + sample.entries() + "/" + quota.maxEntries());
                try {
                    jobKill.run();
                } catch (RuntimeException ignored) {
                    // The caller terminates the process tree as well; a failed kill must not hide the breach.
                }
                return;
            }
            try {
                // Supervision must never cost more than a bounded share of a core. The walk itself is
                // bounded by the entry budget, so backing off proportionally keeps the breach latency
                // bounded too: at most max(samplePeriod, IDLE_RATIO x boundedWalk).
                Thread.sleep(Math.max(quota.samplePeriod().toMillis(), IDLE_RATIO * elapsedMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Sample sample() {
        long[] totals = {0L, 0L};
        for (Path root : roots) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.walkFileTree(root, Set.<FileVisitOption>of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        return account(0L);
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        return account(attributes.isRegularFile() ? attributes.size() : 0L);
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        // Provider files disappear concurrently; an unreadable entry still costs one entry.
                        return account(0L);
                    }

                    private FileVisitResult account(long bytes) {
                        totals[0] = saturatingAdd(totals[0], bytes);
                        totals[1] = saturatingAdd(totals[1], 1L);
                        return totals[0] > quota.maxBytes() || totals[1] > quota.maxEntries()
                                ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // A root that vanished or became unreadable is accounted on the next sample.
            }
            if (totals[0] > quota.maxBytes() || totals[1] > quota.maxEntries()) break;
        }
        return new Sample(totals[0], totals[1]);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private record Sample(long bytes, long entries) {
    }
}
