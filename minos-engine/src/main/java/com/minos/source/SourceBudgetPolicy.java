package com.minos.source;

import java.io.IOException;
import java.util.Objects;

/** Shared end-to-end budget for observable project source traversal. */
public record SourceBudgetPolicy(long maxFiles, long maxBytes) {

    public static final long DEFAULT_MAX_FILES = 100_000L;
    public static final long DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L;
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
        private long files;
        private long bytes;

        private Tracker(SourceBudgetPolicy policy, String boundary) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.boundary = boundary == null || boundary.isBlank() ? "source traversal" : boundary;
        }

        public void accountRegularFile(long sizeBytes) throws IOException {
            if (sizeBytes < 0L) {
                throw new IOException(boundary + " observed a negative file size");
            }
            try {
                files = Math.addExact(files, 1L);
                bytes = Math.addExact(bytes, sizeBytes);
            } catch (ArithmeticException exception) {
                throw new IOException(boundary + " source budget counter overflow", exception);
            }
            if (files > policy.maxFiles() || bytes > policy.maxBytes()) {
                throw new IOException(boundary + " exceeds source budget: files=" + files
                        + "/" + policy.maxFiles() + ", bytes=" + bytes + "/" + policy.maxBytes());
            }
        }

        public long files() {
            return files;
        }

        public long bytes() {
            return bytes;
        }
    }
}
