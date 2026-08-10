package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedArtifactBundleStoreTest {

    @Test
    void roundTripsStrictManifestAndArtifactThenUsesVerifiedCache(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest, artifact);

        var first = store.accept(bundle);
        var second = store.accept(bundle);
        try {
            assertEquals(manifest, first.manifest());
            assertEquals("scip-one", Files.readString(first.artifact()));
            assertFalse(first.cacheHit());
            assertTrue(second.cacheHit());
            assertEquals(first.cacheKey(), second.cacheKey());
            assertEquals(DistributedArtifactBundleStore.sha256(bundle), second.bundleSha256());
        } finally {
            store.release(second);
            store.release(first);
        }
    }

    @Test
    void rejectsTamperingUnknownEntriesAndOversize(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "trusted");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path valid = store.createBundle(temp.resolve("valid.zip"), manifest, artifact);

        Map<String, byte[]> entries = readEntries(valid);
        entries.put(DistributedArtifactManifest.ARTIFACT_PATH, "tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path tampered = writeEntries(temp.resolve("tampered.zip"), entries);
        assertThrows(java.io.IOException.class, () -> store.accept(tampered));

        Map<String, byte[]> unsafe = new LinkedHashMap<>(readEntries(valid));
        unsafe.put("../escape", new byte[]{1});
        assertThrows(java.io.IOException.class, () -> store.accept(writeEntries(temp.resolve("unsafe.zip"), unsafe)));

        Path large = Files.write(temp.resolve("large.scip"), new byte[65]);
        DistributedArtifactManifest largeManifest = manifest(
                "scip-java", large, Instant.parse("2026-07-29T00:00:02Z"));
        assertThrows(java.io.IOException.class, () ->
                store.createBundle(temp.resolve("large.zip"), largeManifest, large));
    }

    @Test
    void evictsOldestArtifactAfterItsLeaseIsReleased(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 1);
        Path firstArtifact = Files.writeString(temp.resolve("first.scip"), "first");
        Path secondArtifact = Files.writeString(temp.resolve("second.scip"), "second");
        var first = store.accept(store.createBundle(
                temp.resolve("first.zip"),
                manifest("provider-one", firstArtifact, Instant.parse("2026-07-29T00:00:01Z")),
                firstArtifact
        ));
        store.release(first);
        var second = store.accept(store.createBundle(
                temp.resolve("second.zip"),
                manifest("provider-two", secondArtifact, Instant.parse("2026-07-29T00:00:02Z")),
                secondArtifact
        ));
        try {
            assertFalse(Files.exists(first.artifact()));
            assertTrue(Files.isRegularFile(second.artifact()));
        } finally {
            store.release(second);
        }
    }

    @Test
    void refusesToEvictAnArtifactWithAnActiveLease(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 1);
        Path firstArtifact = Files.writeString(temp.resolve("first-active.scip"), "first");
        Path secondArtifact = Files.writeString(temp.resolve("second-active.scip"), "second");
        var first = store.accept(store.createBundle(
                temp.resolve("first-active.zip"),
                manifest("provider-active-one", firstArtifact, Instant.parse("2026-07-29T00:00:01Z")),
                firstArtifact));
        try {
            assertThrows(java.io.IOException.class, () -> store.accept(store.createBundle(
                    temp.resolve("second-active.zip"),
                    manifest("provider-active-two", secondArtifact, Instant.parse("2026-07-29T00:00:02Z")),
                    secondArtifact)));
            assertTrue(Files.isRegularFile(first.artifact()));
        } finally {
            store.release(first);
        }
    }

    private static DistributedArtifactBundleStore store(Path temp, int maxEntries) throws Exception {
        return new DistributedArtifactBundleStore(
                temp.resolve("home"),
                new DistributedArtifactCachePolicy(maxEntries, 4096, 64)
        );
    }

    private static DistributedArtifactManifest manifest(String provider, Path artifact, Instant completed)
            throws Exception {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V1,
                UUID.nameUUIDFromBytes(provider.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "https://github.com/acme/demo.git",
                "a".repeat(40),
                Language.JAVA,
                provider,
                "1.0.0",
                "worker-one",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerNetworkPolicy.ALLOW,
                false,
                Instant.parse("2026-07-29T00:00:00Z"),
                completed,
                DistributedArtifactManifest.ARTIFACT_PATH,
                Files.size(artifact),
                DistributedArtifactBundleStore.sha256(artifact)
        );
    }

    private static Map<String, byte[]> readEntries(Path zipFile) throws Exception {
        Map<String, byte[]> values = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zip.transferTo(output);
                values.put(entry.getName(), output.toByteArray());
                zip.closeEntry();
            }
        }
        return values;
    }

    private static Path writeEntries(Path zipFile, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return zipFile;
    }
}
