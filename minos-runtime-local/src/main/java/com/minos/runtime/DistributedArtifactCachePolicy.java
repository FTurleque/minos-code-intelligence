package com.minos.runtime;

/** Limits for versioned distributed artifact bundles and their local verified cache. */
public record DistributedArtifactCachePolicy(
        int maxEntries,
        long maxCacheBytes,
        long maxArtifactBytes
) {

    public static final DistributedArtifactCachePolicy DEFAULT = new DistributedArtifactCachePolicy(
            32,
            5L * 1024L * 1024L * 1024L,
            2L * 1024L * 1024L * 1024L
    );

    public DistributedArtifactCachePolicy {
        if (maxEntries < 1 || maxEntries > 1024) {
            throw new IllegalArgumentException("maxEntries must be between 1 and 1024");
        }
        if (maxArtifactBytes < 1 || maxArtifactBytes > 100L * 1024L * 1024L * 1024L) {
            throw new IllegalArgumentException("maxArtifactBytes must be between 1 byte and 100 GiB");
        }
        if (maxCacheBytes < maxArtifactBytes || maxCacheBytes > 1024L * 1024L * 1024L * 1024L) {
            throw new IllegalArgumentException("maxCacheBytes must cover one artifact and stay below 1 TiB");
        }
    }
}
