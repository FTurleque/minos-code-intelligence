package com.minos.runtime;

import com.minos.io.FileTreeOperations;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded retention for MINOS-owned {@code MINOS_HOME/runs/<runId>} diagnostic directories. */
final class RunDirectoryRetention {

    static final Policy DEFAULT = new Policy(16, 4L * 1024L * 1024L * 1024L, Duration.ofDays(7));
    private static final long MAX_SCAN_ENTRIES_PER_RUN = 1_000_000L;
    private static final int MAX_RUN_ROOT_SCAN_ENTRIES = 4_096;

    private RunDirectoryRetention() {
    }

    static void prune(Path runsRoot, Path protectedRunRoot) throws IOException {
        prune(runsRoot, protectedRunRoot, DEFAULT, Instant.now());
    }

    static void prune(Path runsRoot, Path protectedRunRoot, Policy policy, Instant now) throws IOException {
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

        List<Entry> entries = new ArrayList<>();
        try (var children = Files.list(root)) {
            var iterator = children.iterator();
            int scanned = 0;
            while (iterator.hasNext()) {
                Path child = iterator.next();
                if (++scanned > MAX_RUN_ROOT_SCAN_ENTRIES) {
                    throw new IOException("MINOS runs root exceeds entry scan limit");
                }
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                Path normalized = child.toAbsolutePath().normalize();
                if (protectedRoot != null && normalized.equals(protectedRoot)) continue;
                entries.add(new Entry(
                        normalized,
                        Files.getLastModifiedTime(normalized),
                        sizeOf(normalized)
                ));
            }
        }
        entries.sort(Comparator.comparing(Entry::lastModified).thenComparing(entry -> entry.path().toString()));

        long retainedBytes = 0L;
        for (Entry entry : entries) retainedBytes = saturatingAdd(retainedBytes, entry.bytes());
        int retainedCount = entries.size();
        Instant cutoff = now.minus(policy.maxAge());

        for (Entry entry : entries) {
            boolean expired = entry.lastModified().toInstant().isBefore(cutoff);
            boolean overCount = retainedCount > policy.maxEntries();
            boolean overBytes = retainedBytes > policy.maxBytes();
            if (!expired && !overCount && !overBytes) continue;
            deleteTree(root, entry.path());
            retainedCount--;
            retainedBytes = entry.bytes() >= retainedBytes ? 0L : retainedBytes - entry.bytes();
        }
    }

    static void deleteTree(Path runsRoot, Path target) throws IOException {
        Path root = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IOException("refusing to delete outside the MINOS runs root");
        }
        FileTreeOperations.deleteRecursively(normalized);
    }

    private static long sizeOf(Path run) throws IOException {
        long[] bytes = {0L};
        long[] entries = {0L};
        Files.walkFileTree(run, new SimpleFileVisitor<>() {
            private void accountEntry() throws IOException {
                entries[0]++;
                if (entries[0] > MAX_SCAN_ENTRIES_PER_RUN) {
                    throw new IOException("retained run directory exceeds traversal scan limit: " + run);
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                accountEntry();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                accountEntry();
                if (attributes.isRegularFile()) bytes[0] = saturatingAdd(bytes[0], attributes.size());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                accountEntry();
                throw exception;
            }
        });
        return bytes[0];
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
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

    private record Entry(Path path, FileTime lastModified, long bytes) {
    }
}
