package com.minos.runtime;

import com.minos.io.FileTreeOperations;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded retention for MINOS-owned {@code MINOS_HOME/runs/<runId>} diagnostic directories.
 *
 * <p>A previous run is untrusted residue: a hostile provider may have left millions of entries
 * behind. Retention therefore never lets such a run become a denial of service against every future
 * indexation. A run whose measurement exceeds the scan budget, or that cannot be read at all, is not
 * an error that aborts pruning — it is immediately classified as reclaimable and deleted first.
 * Deletion itself is bounded per invocation; whatever does not fit is moved into a quarantine
 * directory inside the runs root with a constant-cost rename and finished by later invocations.</p>
 *
 * <p>Nothing outside {@code runsRoot} is ever deleted and symbolic links are never followed.</p>
 */
final class RunDirectoryRetention {

    static final Policy DEFAULT = new Policy(16, 4L * 1024L * 1024L * 1024L, Duration.ofDays(7));
    static final String QUARANTINE_DIRECTORY = ".quarantine";
    static final long MAX_SCAN_ENTRIES_PER_RUN = 1_000_000L;
    static final int MAX_RUN_ROOT_SCAN_ENTRIES = 4_096;
    static final long MAX_DELETE_ENTRIES_PER_PRUNE = 250_000L;
    static final Budgets DEFAULT_BUDGETS =
            new Budgets(MAX_SCAN_ENTRIES_PER_RUN, MAX_RUN_ROOT_SCAN_ENTRIES, MAX_DELETE_ENTRIES_PER_PRUNE);

    private RunDirectoryRetention() {
    }

    static void prune(Path runsRoot, Path protectedRunRoot) throws IOException {
        prune(runsRoot, protectedRunRoot, DEFAULT, Instant.now());
    }

    static void prune(Path runsRoot, Path protectedRunRoot, Policy policy, Instant now) throws IOException {
        prune(runsRoot, protectedRunRoot, policy, now, DEFAULT_BUDGETS);
    }

    static void prune(
            Path runsRoot,
            Path protectedRunRoot,
            Policy policy,
            Instant now,
            Budgets budgets
    ) throws IOException {
        Objects.requireNonNull(budgets, "budgets");
        Path root = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        Path protectedRoot = protectedRunRoot == null
                ? null
                : protectedRunRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        Files.createDirectories(root);
        if (protectedRoot != null && !protectedRoot.startsWith(root)) {
            throw new IOException("protected run root escapes MINOS runs root");
        }

        DeletionBudget budget = new DeletionBudget(budgets.maxDeleteEntriesPerPrune());
        drainQuarantine(root, budget);

        List<Entry> entries = new ArrayList<>();
        boolean truncatedScan = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            long scanned = 0;
            for (Path child : children) {
                if (++scanned > budgets.maxRunRootScanEntries()) {
                    // A truncated listing must never abort retention: the entries already observed
                    // are still pruned and the remainder is measured on the next invocation.
                    truncatedScan = true;
                    break;
                }
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                Path normalized = child.toAbsolutePath().normalize();
                if (protectedRoot != null && normalized.equals(protectedRoot)) continue;
                if (QUARANTINE_DIRECTORY.equals(String.valueOf(normalized.getFileName()))) continue;
                entries.add(measure(normalized, budgets.maxScanEntriesPerRun()));
            }
        }

        // Unreadable or oversized residue is reclaimed first: it is the only thing that can starve
        // the next indexation, and it can never be trusted to stay within the scan budget.
        entries.sort(Comparator
                .comparing(Entry::reclaimFirst).reversed()
                .thenComparing(Entry::lastModified)
                .thenComparing(entry -> entry.path().toString()));

        long retainedBytes = 0L;
        for (Entry entry : entries) retainedBytes = saturatingAdd(retainedBytes, entry.bytes());
        int retainedCount = entries.size();
        Instant cutoff = now.minus(policy.maxAge());

        for (Entry entry : entries) {
            boolean expired = entry.lastModified().toInstant().isBefore(cutoff);
            boolean overCount = retainedCount > policy.maxEntries() || truncatedScan;
            boolean overBytes = retainedBytes > policy.maxBytes();
            if (!entry.reclaimFirst() && !expired && !overCount && !overBytes) continue;
            reclaim(root, entry.path(), budget);
            retainedCount--;
            retainedBytes = entry.bytes() >= retainedBytes ? 0L : retainedBytes - entry.bytes();
        }
    }

    /**
     * Deletes a MINOS-owned run tree, or quarantines the remainder when the per-invocation
     * deletion budget is exhausted. Never throws: retention must not block indexing.
     */
    private static void reclaim(Path runsRoot, Path target, DeletionBudget budget) {
        try {
            if (budget.exhausted()) {
                quarantine(runsRoot, target);
                return;
            }
            deleteTree(runsRoot, target, budget);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) quarantine(runsRoot, target);
        } catch (IOException exception) {
            try {
                quarantine(runsRoot, target);
            } catch (IOException failure) {
                System.err.println("MINOS could not reclaim run directory " + target + ": " + failure.getMessage());
            }
        }
    }

    private static void quarantine(Path runsRoot, Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path quarantine = runsRoot.resolve(QUARANTINE_DIRECTORY);
        Files.createDirectories(quarantine);
        Path destination = quarantine.resolve("run-" + UUID.randomUUID());
        Files.move(target, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void drainQuarantine(Path runsRoot, DeletionBudget budget) {
        Path quarantine = runsRoot.resolve(QUARANTINE_DIRECTORY);
        if (!Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(quarantine)) {
            for (Path child : children) {
                if (budget.exhausted()) return;
                try {
                    deleteTree(runsRoot, child.toAbsolutePath().normalize(), budget);
                } catch (IOException exception) {
                    System.err.println("MINOS could not drain quarantined run " + child + ": "
                            + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            System.err.println("MINOS could not enumerate the run quarantine: " + exception.getMessage());
        }
    }

    static void deleteTree(Path runsRoot, Path target) throws IOException {
        deleteTree(runsRoot, target, new DeletionBudget(Long.MAX_VALUE));
    }

    private static void deleteTree(Path runsRoot, Path target, DeletionBudget budget) throws IOException {
        Path root = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IOException("refusing to delete outside the MINOS runs root");
        }
        if (budget.unbounded()) {
            FileTreeOperations.deleteRecursively(normalized);
            return;
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!budget.consume()) return FileVisitResult.TERMINATE;
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return budget.consume() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (!budget.consume()) return FileVisitResult.TERMINATE;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Measures one retained run without ever exceeding the traversal budget. An oversized or
     * unreadable run is reported as reclaimable instead of failing the whole prune.
     */
    private static Entry measure(Path run, long maxScanEntries) {
        long[] bytes = {0L};
        long[] entries = {0L};
        boolean[] reclaimFirst = {false};
        FileTime lastModified;
        try {
            lastModified = Files.getLastModifiedTime(run, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return new Entry(run, FileTime.from(Instant.EPOCH), 0L, true);
        }
        try {
            Files.walkFileTree(run, new SimpleFileVisitor<>() {
                private FileVisitResult account(long size) {
                    entries[0]++;
                    bytes[0] = saturatingAdd(bytes[0], size);
                    if (entries[0] > maxScanEntries) {
                        reclaimFirst[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

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
                    reclaimFirst[0] = true;
                    return FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException | RuntimeException exception) {
            reclaimFirst[0] = true;
        }
        return new Entry(run, lastModified, bytes[0], reclaimFirst[0]);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    /** Bounds that keep retention itself cheap, whatever a previous hostile run left behind. */
    record Budgets(long maxScanEntriesPerRun, long maxRunRootScanEntries, long maxDeleteEntriesPerPrune) {
        Budgets {
            if (maxScanEntriesPerRun < 1L || maxRunRootScanEntries < 1L || maxDeleteEntriesPerPrune < 1L) {
                throw new IllegalArgumentException("run retention budgets must be positive");
            }
        }
    }

    record Policy(int maxEntries, long maxBytes, Duration maxAge) {
        Policy {
            if (maxEntries < 1 || maxBytes < 1L) {
                throw new IllegalArgumentException("run retention limits must be positive");
            }
            maxAge = Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.isZero() || maxAge.isNegative()) {
                throw new IllegalArgumentException("run retention maxAge must be positive");
            }
        }
    }

    private static final class DeletionBudget {
        private final long limit;
        private long consumed;

        private DeletionBudget(long limit) {
            this.limit = limit;
        }

        private boolean unbounded() {
            return limit == Long.MAX_VALUE;
        }

        private boolean exhausted() {
            return !unbounded() && consumed >= limit;
        }

        private boolean consume() {
            if (exhausted()) return false;
            consumed++;
            return true;
        }
    }

    private record Entry(Path path, FileTime lastModified, long bytes, boolean reclaimFirst) {
    }
}
