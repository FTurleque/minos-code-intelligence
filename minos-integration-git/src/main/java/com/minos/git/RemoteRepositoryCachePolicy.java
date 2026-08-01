package com.minos.git;

/** Bounded, rebuildable remote checkout cache policy. */
public record RemoteRepositoryCachePolicy(int maxEntries, long maxBytes) {

    public static final RemoteRepositoryCachePolicy DEFAULT = new RemoteRepositoryCachePolicy(
            8,
            10L * 1024L * 1024L * 1024L
    );

    public RemoteRepositoryCachePolicy {
        if (maxEntries < 1 || maxEntries > 256) {
            throw new IllegalArgumentException("maxEntries must be between 1 and 256");
        }
        if (maxBytes < 1024L * 1024L || maxBytes > 1024L * 1024L * 1024L * 1024L) {
            throw new IllegalArgumentException("maxBytes must be between 1 MiB and 1 TiB");
        }
    }
}
