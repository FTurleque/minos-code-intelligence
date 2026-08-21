package com.minos.runtime;

import com.minos.io.FileTreeOperations;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
 *
 * <p>Loss of measurement visibility is itself a containment breach. A provider controls the
 * writable roots and may try to make a directory unreadable while retaining an already-open file
 * descriptor below it. Treating that subtree as zero bytes would make the quota bypassable, so an
 * access/inspection failure kills the whole contained job. A child entry that simply disappears
 * during the concurrent walk is different: those bytes no longer occupy the filesystem, so a
 * {@link NoSuchFileException} for a non-root entry is tolerated.</p>
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
            if (root != null) addNormalizedRoot(normalized, root);
        }
        ProviderWriteQuotaSupervisor supervisor =
                new ProviderWriteQuotaSupervisor(List.copyOf(normalized), quota, jobKill);
        supervisor.thread.start();
        return supervisor;
    }

    /** A writable root is kept only if it is not already covered by a shorter, already-kept root. */
    private static void addNormalizedRoot(Set<Path> normalized, Path root) {
        Path candidate = root.toAbsolutePath().normalize();
        if (normalized.stream().anyMatch(candidate::startsWith)) return;
        normalized.removeIf(existing -> existing.startsWith(candidate));
        normalized.add(candidate);
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
            if (sample.visibilityFailure() != null) {
                breachAndKill("provider write quota visibility lost: " + sample.visibilityFailure()
                        + "; bytes=" + sample.bytes() + ", entries=" + sample.entries());
                return;
            }
            if (sample.bytes() > quota.maxBytes() || sample.entries() > quota.maxEntries()) {
                breachAndKill("provider exceeded its write quota: bytes=" + sample.bytes()
                        + "/" + quota.maxBytes() + ", entries=" + sample.entries() + "/" + quota.maxEntries());
                return;
            }
            try {
                Thread.sleep(Math.max(quota.samplePeriod().toMillis(), IDLE_RATIO * elapsedMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void breachAndKill(String description) {
        if (!breach.compareAndSet(null, description)) return;
        try {
            jobKill.run();
        } catch (RuntimeException ignored) {
            // The caller terminates the process tree as well; a failed kill must not hide the breach.
        }
    }

    private Sample sample() {
        long[] totals = {0L, 0L};
        for (Path root : roots) {
            String visibilityFailure = sampleRoot(root, totals);
            if (visibilityFailure != null) {
                return new Sample(totals[0], totals[1], visibilityFailure);
            }
            if (totals[0] > quota.maxBytes() || totals[1] > quota.maxEntries()) break;
        }
        return new Sample(totals[0], totals[1], null);
    }

    /** Accumulates one writable root's bytes/entries and reports any loss of measurement visibility. */
    private String sampleRoot(Path root, long[] totals) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return "writable root is missing or no longer a physical directory";
        }
        String[] visibilityFailure = {null};
        try {
            Files.walkFileTree(root, Set.<FileVisitOption>of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    FileVisitResult result = account(0L);
                    if (result != FileVisitResult.CONTINUE) return result;
                    return FileTreeOperations.isRecursableDirectory(attributes)
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    return account(attributes.isRegularFile() ? attributes.size() : 0L);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    if (failure instanceof NoSuchFileException && !file.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    totals[1] = saturatingAdd(totals[1], 1L);
                    visibilityFailure[0] = "cannot inspect an entry below a supervised writable root ("
                            + failure.getClass().getSimpleName() + ")";
                    return FileVisitResult.TERMINATE;
                }

                private FileVisitResult account(long bytes) {
                    totals[0] = saturatingAdd(totals[0], bytes);
                    totals[1] = saturatingAdd(totals[1], 1L);
                    return totals[0] > quota.maxBytes() || totals[1] > quota.maxEntries()
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException disappeared) {
            return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    ? null
                    : "writable root disappeared while it was being supervised";
        } catch (IOException | RuntimeException failure) {
            return "cannot enumerate a supervised writable root (" + failure.getClass().getSimpleName() + ")";
        }
        return visibilityFailure[0];
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private record Sample(long bytes, long entries, String visibilityFailure) {
    }
}
