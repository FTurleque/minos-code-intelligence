package com.minos.git;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Enforces the absolute time and filesystem cardinality budgets of one remote clone. */
class RemoteCloneBudget {
    private static final String TIMEOUT_MESSAGE = "remote repository clone exceeds the configured time limit";

    private final Path destination;
    private final long maxBytes;
    private final long maxFiles;
    private final long maxDirectories;
    private final long maxTraversalEntries;
    private final long timeoutNanos;
    private final long startedNanos = System.nanoTime();

    RemoteCloneBudget(Path destination, RemoteRepositoryCachePolicy policy) {
        this.destination = Objects.requireNonNull(destination, "destination");
        RemoteRepositoryCachePolicy limits = Objects.requireNonNull(policy, "policy");
        this.maxBytes = limits.maxBytes();
        this.maxFiles = limits.maxFiles();
        this.maxDirectories = limits.maxDirectories();
        this.maxTraversalEntries = limits.maxTraversalEntries();
        this.timeoutNanos = limits.cloneTimeout().toNanos();
    }

    TreeMetrics checkpoint() throws IOException {
        enforceTimeout();
        if (!Files.exists(destination)) return new TreeMetrics(0L, 0L, 0L, 0L);
        final long[] bytes = {0L};
        final long[] files = {0L};
        final long[] directories = {0L};
        final long[] entries = {0L};
        Files.walkFileTree(destination, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                enforceTimeout();
                entries[0] = increment(entries[0], "traversal entry");
                directories[0] = increment(directories[0], "directory");
                enforceCardinality(files[0], directories[0], entries[0]);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                enforceTimeout();
                entries[0] = increment(entries[0], "traversal entry");
                files[0] = increment(files[0], "file");
                enforceCardinality(files[0], directories[0], entries[0]);
                if (attributes.isRegularFile()) {
                    bytes[0] = checkedAdd(bytes[0], attributes.size());
                    if (bytes[0] > maxBytes) {
                        throw new IOException("remote repository exceeds the configured clone byte limit");
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                enforceTimeout();
                entries[0] = increment(entries[0], "traversal entry");
                enforceCardinality(files[0], directories[0], entries[0]);
                throw failure;
            }
        });
        enforceTimeout();
        return new TreeMetrics(bytes[0], files[0], directories[0], entries[0]);
    }

    int transportTimeoutSeconds() {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(timeoutNanos);
        if (TimeUnit.SECONDS.toNanos(seconds) < timeoutNanos) seconds++;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, seconds));
    }

    int remainingTimeoutMillis() throws IOException {
        long remainingNanos = remainingNanos();
        if (remainingNanos <= 0L) throw timeoutFailure();
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (TimeUnit.MILLISECONDS.toNanos(millis) < remainingNanos) millis++;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, millis));
    }

    int clampTimeoutMillis(int configuredMillis) {
        int remaining;
        try {
            remaining = remainingTimeoutMillis();
        } catch (IOException timeout) {
            throw new CloneDeadlineExceededException(timeout);
        }
        return configuredMillis <= 0 ? remaining : Math.min(configuredMillis, remaining);
    }

    void enforceTimeoutUnchecked() {
        try {
            enforceTimeout();
        } catch (IOException timeout) {
            throw new CloneDeadlineExceededException(timeout);
        }
    }

    void enforceTimeout() throws IOException {
        if (remainingNanos() <= 0L) throw timeoutFailure();
    }

    private long remainingNanos() {
        return timeoutNanos - (System.nanoTime() - startedNanos);
    }

    private IOException timeoutFailure() {
        return new IOException(TIMEOUT_MESSAGE);
    }

    private long increment(long value, String counter) throws IOException {
        try {
            return Math.addExact(value, 1L);
        } catch (ArithmeticException exception) {
            throw new IOException("remote repository " + counter + " counter overflow", exception);
        }
    }

    private void enforceCardinality(long files, long directories, long traversalEntries) throws IOException {
        if (files > maxFiles) {
            throw new IOException("remote repository exceeds the configured clone file limit");
        }
        if (directories > maxDirectories) {
            throw new IOException("remote repository exceeds the configured clone directory limit");
        }
        if (traversalEntries > maxTraversalEntries) {
            throw new IOException("remote repository exceeds the configured clone traversal entry limit");
        }
    }

    private static long checkedAdd(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException("remote cache byte counter overflow", exception);
        }
    }

    record TreeMetrics(long bytes, long files, long directories, long traversalEntries) {
    }

    private static final class CloneDeadlineExceededException extends RuntimeException {
        private CloneDeadlineExceededException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
