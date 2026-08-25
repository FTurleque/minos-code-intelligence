package com.minos.source;

import java.io.IOException;
import java.util.Objects;

/** Shared end-to-end budget for observable project source traversal. */
public record SourceBudgetPolicy(long maxFiles, long maxBytes) {

    public static final long DEFAULT_MAX_FILES = 100_000L;
    public static final long DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long TRAVERSAL_ENTRY_MULTIPLIER = 8L;
    public static final SourceBudgetPolicy DEFAULT = new SourceBudgetPolicy(
            DEFAULT_MAX_FILES,
            DEFAULT_MAX_BYTES
    );

    public SourceBudgetPolicy {
        if (maxFiles < 1L || maxBytes < 1L) {
            throw new IllegalArgumentException("source budget limits must be positive");
        }
    }

    public Tracker tracker(String boundary) {
        return new Tracker(this, boundary);
    }

    public static final class Tracker {
        private final SourceBudgetPolicy policy;
        private final String boundary;
        private final long maxTraversalEntries;
        private long files;
        private long bytes;
        private long traversalEntries;

        private Tracker(SourceBudgetPolicy policy, String boundary) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.boundary = boundary == null || boundary.isBlank() ? "source traversal" : boundary;
            this.maxTraversalEntries = saturatingMultiply(policy.maxFiles(), TRAVERSAL_ENTRY_MULTIPLIER);
        }

        /** Accounts one regular file using trusted metadata only. */
        public void accountRegularFile(long sizeBytes) throws IOException {
            accountFile();
            accountBytes(sizeBytes);
        }

        /** Starts accounting one regular file whose bytes will be consumed incrementally. */
        public void accountFile() throws IOException {
            try {
                files = Math.addExact(files, 1L);
            } catch (ArithmeticException exception) {
                throw new IOException(boundary + " source file counter overflow", exception);
            }
            enforce();
        }

        /** Accounts bytes as they are actually read or copied. */
        public void accountBytes(long byteCount) throws IOException {
            if (byteCount < 0L) {
                throw new IOException(boundary + " observed a negative byte count");
            }
            try {
                bytes = Math.addExact(bytes, byteCount);
            } catch (ArithmeticException exception) {
                throw new IOException(boundary + " source byte counter overflow", exception);
            }
            enforce();
        }

        /**
         * Accounts every filesystem entry visited, including ignored entries.
         * This prevents a very large ignored tree from consuming unbounded traversal CPU while
         * preserving ignore negation semantics that may require walking a directory.
         */
        public void accountTraversalEntry() throws IOException {
            try {
                traversalEntries = Math.addExact(traversalEntries, 1L);
            } catch (ArithmeticException exception) {
                throw new IOException(boundary + " traversal counter overflow", exception);
            }
            if (traversalEntries > maxTraversalEntries) {
                throw new IOException(boundary + " exceeds traversal budget: entries=" + traversalEntries
                        + "/" + maxTraversalEntries);
            }
        }

        public long files() {
            return files;
        }

        public long bytes() {
            return bytes;
        }

        public long traversalEntries() {
            return traversalEntries;
        }

        private void enforce() throws IOException {
            if (files > policy.maxFiles() || bytes > policy.maxBytes()) {
                throw new IOException(boundary + " exceeds source budget: files=" + files
                        + "/" + policy.maxFiles() + ", bytes=" + bytes + "/" + policy.maxBytes());
            }
        }

        private static long saturatingMultiply(long value, long multiplier) {
            try {
                return Math.multiplyExact(value, multiplier);
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }
    }
}
