package com.minos.runtime.local.distributed;

import com.minos.io.ConfinedFileOpener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Test support: a bundle store whose every read of an artifact, a bundle or a cache entry is recorded, so a test can
 * count how many times each file was opened between two calls.
 */
public final class CountingBundleStores {

    private CountingBundleStores() {
    }

    /** A store under {@code minosHome} that appends the normalized path of every file it opens to {@code opened}. */
    public static DistributedArtifactBundleStore countingStore(
            Path minosHome, DistributedArtifactCachePolicy policy, List<Path> opened) throws IOException {
        return new DistributedArtifactBundleStore(minosHome, policy, file -> {
            opened.add(file.toAbsolutePath().normalize());
            return ConfinedFileOpener.openRegularFileNoFollow(file);
        });
    }
}
