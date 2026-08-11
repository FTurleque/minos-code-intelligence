package com.minos.runtime;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
