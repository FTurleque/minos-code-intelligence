package com.minos.git;

import java.time.Duration;

/** Bounded, rebuildable remote checkout cache policy. */
public record RemoteRepositoryCachePolicy(
        int maxEntries,
        long maxBytes,
        long maxFiles,
        long maxDirectories,
        long maxTraversalEntries,
        Duration cloneTimeout
) {

    public static final RemoteRepositoryCachePolicy DEFAULT = new RemoteRepositoryCachePolicy(
            8,
            10L * 1024L * 1024L * 1024L,
            250_000L,
            100_000L,
            500_000L,
            Duration.ofMinutes(10)
    );

    /** Compatibility constructor retaining the product cardinality and timeout defaults. */
    public RemoteRepositoryCachePolicy(int maxEntries, long maxBytes) {
        this(maxEntries, maxBytes, DEFAULT.maxFiles, DEFAULT.maxDirectories,
                DEFAULT.maxTraversalEntries, DEFAULT.cloneTimeout);
    }

    public RemoteRepositoryCachePolicy {
        if (maxEntries < 1 || maxEntries > 256) {
            throw new IllegalArgumentException("maxEntries must be between 1 and 256");
        }
        if (maxBytes < 1024L * 1024L || maxBytes > 1024L * 1024L * 1024L * 1024L) {
            throw new IllegalArgumentException("maxBytes must be between 1 MiB and 1 TiB");
        }
        if (maxFiles < 1L || maxFiles > 10_000_000L) {
            throw new IllegalArgumentException("maxFiles must be between 1 and 10000000");
        }
        if (maxDirectories < 1L || maxDirectories > 10_000_000L) {
            throw new IllegalArgumentException("maxDirectories must be between 1 and 10000000");
        }
        if (maxTraversalEntries < Math.max(maxFiles, maxDirectories)
                || maxTraversalEntries > 20_000_000L) {
            throw new IllegalArgumentException(
                    "maxTraversalEntries must cover file/directory limits and not exceed 20000000");
        }
        if (cloneTimeout == null || cloneTimeout.isNegative() || cloneTimeout.isZero()
                || cloneTimeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("cloneTimeout must be positive and at most 2 hours");
        }
    }
}
