package com.minos.runtime.local.distributed;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.PrivateLocalStorage;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundle is written to a name of its own in the folder of the caller and published over the target; the extracted
 * artifact of the cache is created new and hardened (AUD-SEC-03).
 */
class DistributedArtifactBundlePublicationTest {

    private static DistributedArtifactBundleStore store(Path temp) throws Exception {
        return new DistributedArtifactBundleStore(
                temp.resolve("home"), new DistributedArtifactCachePolicy(2, 4096, 64));
    }

    private static DistributedArtifactManifest manifest(Path artifact) throws Exception {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                UUID.nameUUIDFromBytes("scip-java".getBytes(StandardCharsets.UTF_8)),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
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

    @Test
    void theBundleIsTheOnlyEntryLeftInTheFolderOfTheCaller(@TempDir Path temp) throws Exception {
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        Path outputFolder = Files.createDirectory(temp.resolve("out"));

        Path bundle = store(temp).createBundle(outputFolder.resolve("artifact.zip"), manifest(artifact), artifact);

        try (Stream<Path> entries = Files.list(outputFolder)) {
            assertEquals(List.of(bundle.getFileName().toString()),
                    entries.map(path -> path.getFileName().toString()).toList(),
                    "no temporary of the publication is left beside the bundle");
        }
    }

    @Test
    void aBundleOverAnExistingTargetReplacesItWhole(@TempDir Path temp) throws Exception {
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        Path target = Files.writeString(temp.resolve("artifact.zip"), "an older, partial bundle");
        DistributedArtifactBundleStore store = store(temp);

        store.createBundle(target, manifest(artifact), artifact);

        var accepted = store.accept(target);
        try {
            assertEquals("scip-one", Files.readString(accepted.artifact()));
        } finally {
            store.release(accepted);
        }
    }

    @Test
    void theExtractedArtifactOfTheCacheIsPrivate(@TempDir Path temp) throws Exception {
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactBundleStore store = store(temp);
        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest(artifact), artifact);

        var accepted = store.accept(bundle);
        try {
            assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(accepted.artifact()));
            assertTrue(Files.isRegularFile(accepted.artifact()));
        } finally {
            store.release(accepted);
        }
    }
}
