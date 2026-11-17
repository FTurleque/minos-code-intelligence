package com.minos.runtime.local.distributed;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedArtifactBundleStoreTest {

    @Test
    void acceptIsNotSerializedOnTheWholeStore() throws Exception {
        int modifiers = DistributedArtifactBundleStore.class
                .getMethod("accept", Path.class)
                .getModifiers();
        assertFalse(Modifier.isSynchronized(modifiers),
                "a cross-process lease wait for one cache key must not block every other cache key");
    }

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

    /** MINOS-AUD-A02 (code dormant, ADR 0041) : une extraction d'un processus mort est bornée dans le temps. */
    @Test
    void anExpiredAcceptanceDirectoryIsReclaimedWhereARecentOneAndTheVerifiedCacheAreKept(@TempDir Path temp)
            throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path cacheRoot = temp.resolve("home").resolve("distributed-artifacts");
        Path expired = plantAcceptance(cacheRoot, ".accept-expired", java.time.Duration.ofHours(48));
        Path recent = plantAcceptance(cacheRoot, ".accept-recent", java.time.Duration.ofMinutes(5));
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest, artifact);

        var accepted = store.accept(bundle);
        try {
            assertFalse(Files.exists(expired), "an extraction directory older than the residue lifetime is reclaimed");
            assertTrue(Files.exists(recent), "a recent extraction may belong to a live acceptance");
            assertEquals("scip-one", Files.readString(accepted.artifact()), "the verified cache is never touched");
        } finally {
            store.release(accepted);
        }
    }

    private static Path plantAcceptance(Path cacheRoot, String name, java.time.Duration age) throws java.io.IOException {
        Path directory = Files.createDirectories(cacheRoot.resolve(name).resolve("nested"));
        Files.writeString(directory.resolve("artifact.scip"), "residue");
        java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime.from(Instant.now().minus(age));
        Files.setLastModifiedTime(directory.resolve("artifact.scip"), time);
        Files.setLastModifiedTime(directory, time);
        Files.setLastModifiedTime(directory.getParent(), time);
        return directory.getParent();
    }

    @Test
    void rejectsTamperingUnknownEntriesAndOversize(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "trusted");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path valid = store.createBundle(temp.resolve("valid.zip"), manifest, artifact);

        Map<String, byte[]> entries = readEntries(valid);
        entries.put(DistributedArtifactManifest.ARTIFACT_PATH,
                "tampered".getBytes(StandardCharsets.UTF_8));
        Path tampered = writeEntries(temp.resolve("tampered.zip"), entries);
        assertThrows(java.io.IOException.class, () -> store.accept(tampered));

        Map<String, byte[]> unsafe = new LinkedHashMap<>(readEntries(valid));
        unsafe.put("../escape", new byte[]{1});
        assertThrows(java.io.IOException.class,
                () -> store.accept(writeEntries(temp.resolve("unsafe.zip"), unsafe)));

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
    void refusesToEvictAnArtifactWithAnActiveLeaseWithoutLeavingRejectedEntry(@TempDir Path temp) throws Exception {
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
            assertEquals(1L, cacheEntryCount(temp));
        } finally {
            store.release(first);
        }
    }

    @Test
    void duplicateAndUnknownReleaseCannotConsumeAnotherActiveHandle(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 1);
        Path firstArtifact = Files.writeString(temp.resolve("identity-first.scip"), "first");
        DistributedArtifactManifest firstManifest = manifest(
                "provider-identity-one", firstArtifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path firstBundle = store.createBundle(temp.resolve("identity-first.zip"), firstManifest, firstArtifact);

        var first = store.accept(firstBundle);
        var second = store.accept(firstBundle);
        store.release(first);
        store.release(first);

        var unknown = new DistributedArtifactBundleStore.VerifiedArtifact(
                second.manifest(), second.artifact(), second.cacheKey(), second.cacheHit(), second.bundleSha256());
        store.release(unknown);

        Path competingArtifact = Files.writeString(temp.resolve("identity-competing.scip"), "competing");
        DistributedArtifactManifest competingManifest = manifest(
                "provider-identity-two", competingArtifact, Instant.parse("2026-07-29T00:00:02Z"));
        Path competingBundle = store.createBundle(
                temp.resolve("identity-competing.zip"), competingManifest, competingArtifact);

        assertThrows(java.io.IOException.class, () -> store.accept(competingBundle),
                "the second accepted handle must still hold the cache lease");

        store.release(second);
        var accepted = assertDoesNotThrow(() -> store.accept(competingBundle));
        store.release(accepted);
    }

    @Test
    void exactHashRejectsLengthChangesAgainstExpectedBound(@TempDir Path temp) throws Exception {
        Path artifact = Files.write(temp.resolve("hash-exact.scip"), new byte[65]);

        assertThrows(java.io.IOException.class,
                () -> DistributedArtifactBundleStore.sha256Exact(artifact, 64L, 64L));
        assertThrows(java.io.IOException.class,
                () -> DistributedArtifactBundleStore.sha256Exact(artifact, 66L, 66L));
        assertDoesNotThrow(() -> DistributedArtifactBundleStore.sha256Exact(artifact, 65L, 65L));
    }

    @Test
    void differentScopesProduceDifferentCacheKeys(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 4);
        Path artifact = Files.writeString(temp.resolve("shared.scip"), "shared-content");
        DistributedArtifactManifest rootManifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"), "");
        DistributedArtifactManifest moduleManifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"), "services/catalog");

        Path rootBundle = store.createBundle(temp.resolve("root.zip"), rootManifest, artifact);
        Path moduleBundle = store.createBundle(temp.resolve("module.zip"), moduleManifest, artifact);

        var rootResult = store.accept(rootBundle);
        var moduleResult = store.accept(moduleBundle);
        try {
            assertNotEquals(rootResult.cacheKey(), moduleResult.cacheKey(),
                    "different scopes must produce different cache keys");
            assertEquals("", rootResult.manifest().projectRelativeRoot());
            assertEquals("services/catalog", moduleResult.manifest().projectRelativeRoot());
        } finally {
            store.release(rootResult);
            store.release(moduleResult);
        }
    }

    @Test
    void rejectsTamperedScopeFieldInManifest(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("ok.scip"), "scip-content");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"), "services/catalog");
        Path valid = store.createBundle(temp.resolve("valid.zip"), manifest, artifact);

        Map<String, byte[]> entries = readEntries(valid);
        Properties props = new Properties();
        props.load(new java.io.InputStreamReader(
                new ByteArrayInputStream(entries.get(DistributedArtifactBundleStore.MANIFEST_ENTRY)),
                StandardCharsets.UTF_8));
        props.setProperty("projectRelativeRoot", "/absolute/escape");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            props.store(w, null);
        }
        entries.put(DistributedArtifactBundleStore.MANIFEST_ENTRY, out.toByteArray());
        Path tamperedBundle = writeEntries(temp.resolve("tampered-scope.zip"), entries);

        assertThrows(java.io.IOException.class, () -> store.accept(tamperedBundle));
    }

    @Test
    void v1ManifestAlwaysDecodesAsRootScope(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("v1-artifact.scip"), "v1-content");
        Path bundle = buildV1Bundle(temp.resolve("v1.zip"), "scip-java", artifact);

        var result = store.accept(bundle);
        try {
            assertEquals("", result.manifest().projectRelativeRoot(),
                    "v1 bundles must always decode as root scope");
            assertEquals(DistributedArtifactManifest.FORMAT_V1, result.manifest().format());
        } finally {
            store.release(result);
        }
    }

    // indexation-java-via-wsl › Le paquet produit dans la distribution est vérifié pendant son écriture

    @Test
    void aBundleIsBuiltByReadingItsSourceArtifactOnce(@TempDir Path temp) throws Exception {
        List<Path> opened = new ArrayList<>();
        DistributedArtifactBundleStore store = CountingBundleStores.countingStore(
                temp.resolve("home"), new DistributedArtifactCachePolicy(2, 4096, 64), opened);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));

        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest, artifact);

        assertEquals(1, Collections.frequency(opened, artifact.toAbsolutePath().normalize()),
                "the source artifact is read once, hashed while it is written into the bundle: " + opened);
        var accepted = store.accept(bundle);
        store.release(accepted);
    }

    @Test
    void anArtifactChangedAfterItsManifestPublishesNoBundleAndLeavesNoTemporaryFile(@TempDir Path temp)
            throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Files.writeString(artifact, "scip-two");
        Path output = Files.createDirectories(temp.resolve("out")).resolve("artifact.zip");

        java.io.IOException refusal = assertThrows(java.io.IOException.class,
                () -> store.createBundle(output, manifest, artifact));

        assertEquals("distributed worker artifact checksum does not match its manifest", refusal.getMessage());
        assertFalse(Files.exists(output), "no bundle is published");
        try (var leftovers = Files.list(output.getParent())) {
            assertEquals(0L, leftovers.count(), "no .bundle-*.tmp file is left behind");
        }
    }

    // indexation-java-via-wsl › L'artefact de WSL est lu au plus deux fois entre l'acceptation du paquet et son retour

    @Test
    void acceptanceReadsTheBundleOnceAndReportsItsSha256(@TempDir Path temp) throws Exception {
        List<Path> opened = new ArrayList<>();
        DistributedArtifactBundleStore store = CountingBundleStores.countingStore(
                temp.resolve("home"), new DistributedArtifactCachePolicy(2, 4096, 64), opened);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest, artifact);
        String bundleSha256 = DistributedArtifactBundleStore.sha256(bundle);
        opened.clear();

        var accepted = store.accept(bundle);
        try {
            assertEquals(List.of(bundle.toAbsolutePath().normalize()), opened,
                    "a cache miss reads the bundle once and neither the extracted artifact nor the cache entry");
            assertEquals(bundleSha256, accepted.bundleSha256());
            assertFalse(accepted.cacheHit());
        } finally {
            store.release(accepted);
        }
    }

    @Test
    void aBundleWhoseArtifactDiffersFromItsManifestLeavesNeitherCacheEntryNorExtraction(@TempDir Path temp)
            throws Exception {
        DistributedArtifactBundleStore store = store(temp, 2);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "trusted");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Map<String, byte[]> entries = readEntries(store.createBundle(temp.resolve("valid.zip"), manifest, artifact));
        Map<String, byte[]> sameSize = new LinkedHashMap<>(entries);
        sameSize.put(DistributedArtifactManifest.ARTIFACT_PATH, "TRUSTED".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> otherSize = new LinkedHashMap<>(entries);
        otherSize.put(DistributedArtifactManifest.ARTIFACT_PATH, "trusted!".getBytes(StandardCharsets.UTF_8));

        java.io.IOException checksum = assertThrows(java.io.IOException.class,
                () -> store.accept(writeEntries(temp.resolve("same-size.zip"), sameSize)));
        java.io.IOException size = assertThrows(java.io.IOException.class,
                () -> store.accept(writeEntries(temp.resolve("other-size.zip"), otherSize)));

        assertEquals("transported artifact checksum does not match its manifest", checksum.getMessage());
        assertEquals("transported artifact size does not match its manifest", size.getMessage());
        assertEquals(0L, cacheEntryCount(temp), "no cache entry is created");
        try (var children = Files.list(temp.resolve("home").resolve("distributed-artifacts"))) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".accept-")),
                    "the extraction directory is deleted");
        }
    }

    @Test
    void anIdenticalCacheEntryIsVerifiedAgainBeforeItIsReturned(@TempDir Path temp) throws Exception {
        List<Path> opened = new ArrayList<>();
        DistributedArtifactBundleStore store = CountingBundleStores.countingStore(
                temp.resolve("home"), new DistributedArtifactCachePolicy(2, 4096, 64), opened);
        Path artifact = Files.writeString(temp.resolve("source.scip"), "scip-one");
        DistributedArtifactManifest manifest = manifest(
                "scip-java", artifact, Instant.parse("2026-07-29T00:00:01Z"));
        Path bundle = store.createBundle(temp.resolve("artifact.zip"), manifest, artifact);
        store.release(store.accept(bundle));
        opened.clear();

        var hit = store.accept(bundle);
        try {
            assertTrue(hit.cacheHit());
            assertEquals(1, Collections.frequency(opened, hit.artifact()),
                    "the cached artifact is hashed again before it is returned: " + opened);
            assertEquals(1, Collections.frequency(opened, bundle.toAbsolutePath().normalize()));
        } finally {
            store.release(hit);
        }
    }

    private static long cacheEntryCount(Path temp) throws Exception {
        Path cache = temp.resolve("home").resolve("distributed-artifacts");
        try (var entries = Files.list(cache)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .count();
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
        return manifest(provider, artifact, completed, "");
    }

    private static DistributedArtifactManifest manifest(
            String provider, Path artifact, Instant completed, String scope) throws Exception {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                UUID.nameUUIDFromBytes(provider.getBytes(StandardCharsets.UTF_8)),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                scope,
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

    private static Path buildV1Bundle(Path zipFile, String provider, Path artifact) throws Exception {
        UUID runId = UUID.nameUUIDFromBytes(provider.getBytes(StandardCharsets.UTF_8));
        String sha256 = DistributedArtifactBundleStore.sha256(artifact);

        Properties properties = new Properties();
        properties.setProperty("format", DistributedArtifactManifest.FORMAT_V1);
        properties.setProperty("runId", runId.toString());
        properties.setProperty("projectId", "11111111-1111-1111-1111-111111111111");
        // No projectRelativeRoot — v1 wire format does not carry scope
        properties.setProperty("sourceRepository", "https://github.com/acme/demo.git");
        properties.setProperty("sourceCommit", "a".repeat(40));
        properties.setProperty("language", "JAVA");
        properties.setProperty("providerId", provider);
        properties.setProperty("providerVersion", "1.0.0");
        properties.setProperty("workerId", "worker-one");
        properties.setProperty("isolation", "PROCESS_EPHEMERAL_WORKSPACE");
        properties.setProperty("networkPolicy", "ALLOW");
        properties.setProperty("networkDenyEnforced", "false");
        properties.setProperty("startedAt", "2026-07-29T00:00:00Z");
        properties.setProperty("completedAt", "2026-07-29T00:00:01Z");
        properties.setProperty("artifactPath", "index.scip");
        properties.setProperty("artifactSize", String.valueOf(Files.size(artifact)));
        properties.setProperty("artifactSha256", sha256);

        ByteArrayOutputStream manifestOut = new ByteArrayOutputStream();
        try (OutputStreamWriter w = new OutputStreamWriter(manifestOut, StandardCharsets.UTF_8)) {
            properties.store(w, null);
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip.putNextEntry(new ZipEntry(DistributedArtifactBundleStore.MANIFEST_ENTRY));
            zip.write(manifestOut.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(DistributedArtifactManifest.ARTIFACT_PATH));
            zip.write(Files.readAllBytes(artifact));
            zip.closeEntry();
        }
        return zipFile;
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
