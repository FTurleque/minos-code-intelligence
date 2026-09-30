package com.minos.runtime.local;

import com.minos.io.FileTreeOperations;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
import java.util.Optional;
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
 * <p>A run directory carrying a {@code .resumable} marker (ADR 0039 §5) is a run retained on purpose:
 * either an interrupted run offered for resume, or a run still being produced by another indexation
 * (the marker is written before its first provider and removed when the run ends). It is reclaimed
 * last, only when the count or byte budget still requires it after every unmarked run, or when its
 * lifetime is over. A truncated scan of {@code runs/} is not, by itself, a reason to reclaim it (R5).
 * The single lifetime rule lives in {@link Policy#lifetime(boolean)}: a marked run never lives shorter
 * than an ordinary run and never longer than {@code max(maxAge, resumeTtl)} after the later of its
 * marker and its last write, so a marker can never pin {@code runs/} indefinitely. The marker's own
 * modification time counts because the marker and the artifact digest sidecar are written into the
 * run directory after the artifacts (R1-7).</p>
 *
 * <p>Nothing outside {@code runsRoot} is ever deleted and symbolic links are never followed.</p>
 */
final class RunDirectoryRetention {

    private static final System.Logger LOGGER = System.getLogger(RunDirectoryRetention.class.getName());

    /** Aligned on the resume planner's TTL: the least a marked run is kept (see {@link Policy#lifetime}). */
    static final Duration DEFAULT_RESUME_TTL = Duration.ofHours(24);
    static final Policy DEFAULT = new Policy(16, 4L * 1024L * 1024L * 1024L, Duration.ofDays(7), DEFAULT_RESUME_TTL);
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

        Scan scan = scanRunRoot(root, protectedRoot, budgets);
        List<Entry> entries = scan.entries();

        // Unreadable or oversized residue is reclaimed first: it is the only thing that can starve
        // the next indexation, and it can never be trusted to stay within the scan budget. Runs
        // offered for resume come last, so the budget is re-evaluated after every unmarked run.
        entries.sort(Comparator
                .comparing(Entry::reclaimFirst).reversed()
                .thenComparing(Entry::resumable)
                .thenComparing(Entry::ageReference)
                .thenComparing(entry -> entry.path().toString()));

        long retainedBytes = 0L;
        for (Entry entry : entries) retainedBytes = saturatingAdd(retainedBytes, entry.bytes());
        int retainedCount = entries.size();

        for (Entry entry : entries) {
            boolean expired = entry.ageReference().toInstant().isBefore(now.minus(policy.lifetime(entry.resumable())));
            // A truncated listing means the retained set was not fully measured: it justifies reclaiming
            // the unmarked residue that was observed, never a run held by a marker (R5).
            boolean overCount = retainedCount > policy.maxEntries() || (scan.truncated() && !entry.resumable());
            boolean overBytes = retainedBytes > policy.maxBytes();
            if (!entry.reclaimFirst() && !expired && !overCount && !overBytes) continue;
            reclaim(root, entry.path(), budget);
            retainedCount--;
            retainedBytes = entry.bytes() >= retainedBytes ? 0L : retainedBytes - entry.bytes();
        }
    }

    /**
     * Measures the retained runs without ever exceeding the run-root scan budget. A truncated
     * listing must never abort retention: what was observed is still pruned and the remainder is
     * measured by the next invocation.
     */
    private static Scan scanRunRoot(Path root, Path protectedRoot, Budgets budgets) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            long scanned = 0;
            for (Path child : children) {
                if (++scanned > budgets.maxRunRootScanEntries()) return new Scan(entries, true);
                if (isScannableRun(child, protectedRoot)) {
                    entries.add(measure(child.toAbsolutePath().normalize(), budgets.maxScanEntriesPerRun()));
                }
            }
        }
        return new Scan(entries, false);
    }

    private static boolean isScannableRun(Path child, Path protectedRoot) {
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) return false;
        Path normalized = child.toAbsolutePath().normalize();
        if (normalized.equals(protectedRoot)) return false;
        return !QUARANTINE_DIRECTORY.equals(String.valueOf(normalized.getFileName()));
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
            quarantineQuietly(runsRoot, target);
        }
    }

    private static void quarantineQuietly(Path runsRoot, Path target) {
        try {
            quarantine(runsRoot, target);
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS could not reclaim run directory " + target, failure);
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
                    LOGGER.log(System.Logger.Level.WARNING, "MINOS could not drain quarantined run " + child,
                            exception);
                }
            }
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS could not enumerate the run quarantine", exception);
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
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (FileTreeOperations.isRecursableDirectory(attributes)) return FileVisitResult.CONTINUE;
                // A previous run's Windows junction/reparse point: never descend into it. Deleting
                // the entry itself only removes the reparse point, never the content it points at.
                if (!budget.consume()) return FileVisitResult.TERMINATE;
                Files.deleteIfExists(directory);
                return FileVisitResult.SKIP_SUBTREE;
            }

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
            return new Entry(run, FileTime.from(Instant.EPOCH), 0L, true, Optional.empty());
        }
        Optional<FileTime> resumableSince = resumableSince(run);
        boolean marked = resumableSince.isPresent();
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
                    FileVisitResult result = account(0L);
                    if (result != FileVisitResult.CONTINUE) return result;
                    // A Windows junction/reparse point: account it as one entry but never descend
                    // into whatever it targets, which may be entirely outside this run's tree.
                    return FileTreeOperations.isRecursableDirectory(attributes)
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    return account(attributes.isRegularFile() ? attributes.size() : 0L);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    if (!unreadableEntryMakesRunReclaimable(failure, marked)) return FileVisitResult.CONTINUE;
                    reclaimFirst[0] = true;
                    return FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException | RuntimeException exception) {
            if (unreadableEntryMakesRunReclaimable(exception instanceof IOException io ? io : null, marked)) {
                reclaimFirst[0] = true;
            }
        }
        return new Entry(run, lastModified, bytes[0], reclaimFirst[0], resumableSince);
    }

    /** The interruption time of a run offered for resume: its marker's modification time. */
    private static Optional<FileTime> resumableSince(Path run) {
        Path marker = run.resolve(FileResumableRunMarkers.MARKER_FILE_NAME);
        try {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * Whether an entry that could not be read makes its whole run reclaimable first. It does for
     * unmarked residue (it can never be trusted to stay measurable). It does not for a run held by a
     * marker, which another indexation may still be writing, nor for an entry that merely vanished
     * between the listing and the read, as the temporaries of an atomic publication do.
     */
    static boolean unreadableEntryMakesRunReclaimable(IOException failure, boolean marked) {
        return !marked && !(failure instanceof NoSuchFileException);
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

    record Policy(int maxEntries, long maxBytes, Duration maxAge, Duration resumeTtl) {
        Policy {
            if (maxEntries < 1 || maxBytes < 1L) {
                throw new IllegalArgumentException("run retention limits must be positive");
            }
            maxAge = Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.isZero() || maxAge.isNegative()) {
                throw new IllegalArgumentException("run retention maxAge must be positive");
            }
            resumeTtl = Objects.requireNonNull(resumeTtl, "resumeTtl");
            if (resumeTtl.isZero() || resumeTtl.isNegative()) {
                throw new IllegalArgumentException("run retention resumeTtl must be positive");
            }
        }

        /**
         * The one place that says how long a run directory lives, measured from its reference date.
         * An ordinary run lives {@code maxAge}. A run held by a marker lives the longer of {@code maxAge}
         * and {@code resumeTtl}: holding a run never shortens its life (R5, it used to fall from 7 days
         * to 24 hours), and this is also the explicit upper bound after which even a held run is
         * reclaimed, so a marker can never pin {@code runs/} indefinitely.
         */
        Duration lifetime(boolean marked) {
            return marked && resumeTtl.compareTo(maxAge) > 0 ? resumeTtl : maxAge;
        }

        /** Compatibility constructor: the default resume TTL. */
        Policy(int maxEntries, long maxBytes, Duration maxAge) {
            this(maxEntries, maxBytes, maxAge, DEFAULT_RESUME_TTL);
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

    private record Entry(
            Path path,
            FileTime lastModified,
            long bytes,
            boolean reclaimFirst,
            Optional<FileTime> resumableSince
    ) {
        private boolean resumable() {
            return resumableSince.isPresent();
        }

        /**
         * The time that decides both ordering and expiry: the later of the directory's last write and,
         * for a marked run, its marker's (the marker dates the interruption or the start of a run held
         * by another indexation). It can therefore only extend a marked run's life, never cut it.
         */
        private FileTime ageReference() {
            return resumableSince.filter(since -> since.compareTo(lastModified) > 0).orElse(lastModified);
        }
    }

    private record Scan(List<Entry> entries, boolean truncated) {
    }
}
