package com.minos.runtime.local.distributed;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.PrivateLocalStorage.Privacy;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S5: everything MINOS keeps for a run or a cached bundle is owner-only. These directories and files
 * hold a copy of, or an index derived from, the user's code: Java defaults would leave them readable
 * by other local users. This class covers the distributed bundle cache.
 */
class BundleCacheStoragePrivacyTest {

    private static void assertPrivate(Path path) throws IOException {
        assertEquals(Privacy.ENFORCED, PrivateLocalStorage.privacyOf(path), "not owner-only: " + path.getFileName());
    }

    @Test
    void theBundleCacheItsLeasesAndEveryCachedEntryAreOwnerOnly(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(home);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(artifact);
        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest, artifact);

        var accepted = store.accept(bundle);
        try {
            Path cache = home.resolve("distributed-artifacts");
            Path entry = accepted.artifact().getParent();
            assertPrivate(cache);
            assertPrivate(cache.resolve(".leases"));
            assertPrivate(entry);
            assertPrivate(accepted.artifact());
            assertPrivate(entry.resolve(DistributedArtifactBundleStore.MANIFEST_ENTRY));
            assertPrivate(cache.resolve(".leases").resolve(accepted.cacheKey() + ".lease"));
        } finally {
            store.release(accepted);
        }
    }

    private static DistributedArtifactManifest manifest(Path artifact) throws Exception {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "",
                "https://github.com/acme/demo.git",
                "a".repeat(40),
                Language.JAVA,
                "scip-java",
                "1.0.0",
                "worker-one",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerNetworkPolicy.ALLOW,
                false,
                Instant.parse("2026-07-29T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:01Z"),
                DistributedArtifactManifest.ARTIFACT_PATH,
                Files.size(artifact),
                DistributedArtifactBundleStore.sha256(artifact));
    }
}
