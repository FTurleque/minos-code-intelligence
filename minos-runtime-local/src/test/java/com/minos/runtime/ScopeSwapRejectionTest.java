package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial tests confirming that a worker cannot launder a bundle produced for scope X
 * past a coordinator that requested scope Y (MINOS-02).
 */
class ScopeSwapRejectionTest {

    // RemoteRepositoryRequest.canonicalUri() always appends ".git" — use the canonical form so
    // manifest.sourceRepository() and source.request().canonicalRepositoryUri() agree.
    private static final String REPO = "https://github.com/acme/monorepo.git";
    private static final String COMMIT = "a".repeat(40);

    @Test
    void bundleForModuleBIsRejectedWhenModuleARequested(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp);
        Path registeredRoot = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(registeredRoot.resolve("module-a"));
        Files.createDirectories(registeredRoot.resolve("module-b"));

        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        BundleAndManifest attackBundle = buildBundle(
                temp, store, runId, projectId, "module-b", COMMIT, REPO, 0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeWith(temp, store, runId, projectId, registeredRoot, "module-a", attackBundle));
        assertTrue(failure.getMessage().contains("provenance"),
                "scope swap must be reported as a provenance mismatch");
    }

    @Test
    void bundleForModuleAIsRejectedWhenModuleBRequested(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp);
        Path registeredRoot = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(registeredRoot.resolve("module-a"));
        Files.createDirectories(registeredRoot.resolve("module-b"));

        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        BundleAndManifest attackBundle = buildBundle(
                temp, store, runId, projectId, "module-a", COMMIT, REPO, 1);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeWith(temp, store, runId, projectId, registeredRoot, "module-b", attackBundle));
        assertTrue(failure.getMessage().contains("provenance"));
    }

    @Test
    void rootBundleIsRejectedWhenModuleRequested(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp);
        Path registeredRoot = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(registeredRoot.resolve("services/catalog"));

        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        BundleAndManifest attackBundle = buildBundle(
                temp, store, runId, projectId, "", COMMIT, REPO, 2);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeWith(temp, store, runId, projectId, registeredRoot, "services/catalog", attackBundle));
        assertTrue(failure.getMessage().contains("provenance"));
    }

    @Test
    void moduleBundleIsRejectedWhenRootRequested(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp);
        Path registeredRoot = Files.createDirectories(temp.resolve("project"));

        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        BundleAndManifest attackBundle = buildBundle(
                temp, store, runId, projectId, "services/catalog", COMMIT, REPO, 3);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeWith(temp, store, runId, projectId, registeredRoot, "", attackBundle));
        assertTrue(failure.getMessage().contains("provenance"));
    }

    @Test
    void v1BundleIsRejectedForNonRootRequest(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp);
        Path registeredRoot = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(registeredRoot.resolve("module-a"));

        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        // A v1 bundle always decodes as root scope (projectRelativeRoot = ""); requesting
        // "module-a" (non-root) therefore produces a provenance mismatch.
        BundleAndManifest v1Bundle = buildV1Bundle(temp, store, runId, projectId, COMMIT, REPO);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeWith(temp, store, runId, projectId, registeredRoot, "module-a", v1Bundle));
        assertTrue(failure.getMessage().contains("provenance"),
                "v1 bundles (root scope) must be rejected for any non-root-scope request");
    }

    @Test
    void differentScopeRequestsNeverShareCacheKey(@TempDir Path temp) throws Exception {
        DistributedArtifactBundleStore store = store(temp);
        Path registeredRoot = Files.createDirectories(temp.resolve("project"));
        Path moduleARoot = Files.createDirectories(registeredRoot.resolve("module-a"));
        Path moduleBRoot = Files.createDirectories(registeredRoot.resolve("module-b"));

        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        BundleAndManifest bundleA = buildBundle(temp, store, runId, projectId, "module-a", COMMIT, REPO, 10);
        BundleAndManifest bundleB = buildBundle(temp, store, runId, projectId, "module-b", COMMIT, REPO, 11);

        RemoteRepositoryRequest repoRequest = RemoteRepositoryRequest.of(REPO, "main", COMMIT, "project/module-a", null);
        RemoteMaterialization sourceA = new RemoteMaterialization(
                repoRequest, temp, moduleARoot, "c".repeat(64), false, Instant.parse("2026-07-29T00:00:00Z"));
        RemoteMaterialization sourceB = new RemoteMaterialization(
                repoRequest, temp, moduleBRoot, "c".repeat(64), false, Instant.parse("2026-07-29T00:00:00Z"));

        IndexingExecutionRequest requestA = executionFor(registeredRoot, "module-a", runId, projectId);
        IndexingExecutionRequest requestB = executionFor(registeredRoot, "module-b", runId, projectId);

        DistributedIndexerExecutor executorA = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", sourceA, WorkerNetworkPolicy.ALLOW,
                stubWorker(bundleA), store);
        DistributedIndexerExecutor executorB = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", sourceB, WorkerNetworkPolicy.ALLOW,
                stubWorker(bundleB), store);

        executorA.execute(requestA);
        executorB.execute(requestB);

        String keyA = executorA.verifiedArtifact().orElseThrow().cacheKey();
        String keyB = executorB.verifiedArtifact().orElseThrow().cacheKey();
        assertNotEquals(keyA, keyB,
                "different module scopes must produce different artifact cache keys");
        assertEquals("module-a", executorA.verifiedArtifact().orElseThrow().manifest().projectRelativeRoot());
        assertEquals("module-b", executorB.verifiedArtifact().orElseThrow().manifest().projectRelativeRoot());
    }

    private static void executeWith(
            Path temp,
            DistributedArtifactBundleStore store,
            UUID runId,
            UUID projectId,
            Path registeredRoot,
            String coordinatorScope,
            BundleAndManifest attackBundle
    ) throws Exception {
        Path projectRoot = coordinatorScope.isEmpty()
                ? registeredRoot
                : registeredRoot.resolve(Path.of(coordinatorScope));
        RemoteRepositoryRequest repoRequest = RemoteRepositoryRequest.of(
                REPO, "main", COMMIT, "project", null);
        RemoteMaterialization source = new RemoteMaterialization(
                repoRequest, temp, projectRoot, "c".repeat(64), false, Instant.parse("2026-07-29T00:00:00Z"));
        IndexingExecutionRequest executionRequest = executionFor(registeredRoot, coordinatorScope, runId, projectId);
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", source, WorkerNetworkPolicy.ALLOW,
                stubWorker(attackBundle), store);
        executor.execute(executionRequest);
    }

    private static BundleAndManifest buildBundle(
            Path temp,
            DistributedArtifactBundleStore store,
            UUID runId,
            UUID projectId,
            String scope,
            String commit,
            String sourceRepo,
            int salt
    ) throws Exception {
        byte[] content = ("scip-content-salt-" + salt).getBytes(StandardCharsets.UTF_8);
        Path artifact = Files.write(temp.resolve("artifact-" + salt + ".scip"), content);
        DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                runId, projectId, scope,
                sourceRepo, commit, Language.JAVA,
                "fixture-provider", "1.2.3", "worker-one",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE, WorkerNetworkPolicy.ALLOW, false,
                Instant.parse("2026-07-29T00:00:00Z"), Instant.parse("2026-07-29T00:00:01Z"),
                DistributedArtifactManifest.ARTIFACT_PATH,
                Files.size(artifact), DistributedArtifactBundleStore.sha256(artifact));
        Path bundle = store.createBundle(temp.resolve("bundle-" + salt + ".zip"), manifest, artifact);
        return new BundleAndManifest(bundle, manifest);
    }

    private static BundleAndManifest buildV1Bundle(
            Path temp,
            DistributedArtifactBundleStore store,
            UUID runId,
            UUID projectId,
            String commit,
            String sourceRepo
    ) throws Exception {
        byte[] content = "scip-content-v1".getBytes(StandardCharsets.UTF_8);
        Path artifact = Files.write(temp.resolve("artifact-v1.scip"), content);
        String sha256 = DistributedArtifactBundleStore.sha256(artifact);

        Properties properties = new Properties();
        properties.setProperty("format", DistributedArtifactManifest.FORMAT_V1);
        properties.setProperty("runId", runId.toString());
        properties.setProperty("projectId", projectId.toString());
        // No projectRelativeRoot — v1 wire format
        properties.setProperty("sourceRepository", sourceRepo);
        properties.setProperty("sourceCommit", commit);
        properties.setProperty("language", "JAVA");
        properties.setProperty("providerId", "fixture-provider");
        properties.setProperty("providerVersion", "1.2.3");
        properties.setProperty("workerId", "worker-one");
        properties.setProperty("isolation", "PROCESS_EPHEMERAL_WORKSPACE");
        properties.setProperty("networkPolicy", "ALLOW");
        properties.setProperty("networkDenyEnforced", "false");
        properties.setProperty("startedAt", "2026-07-29T00:00:00Z");
        properties.setProperty("completedAt", "2026-07-29T00:00:01Z");
        properties.setProperty("artifactPath", "index.scip");
        properties.setProperty("artifactSize", String.valueOf(content.length));
        properties.setProperty("artifactSha256", sha256);

        ByteArrayOutputStream manifestOut = new ByteArrayOutputStream();
        try (OutputStreamWriter w = new OutputStreamWriter(manifestOut, StandardCharsets.UTF_8)) {
            properties.store(w, null);
        }
        Path bundlePath = temp.resolve("bundle-v1.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundlePath))) {
            zip.putNextEntry(new ZipEntry(DistributedArtifactBundleStore.MANIFEST_ENTRY));
            zip.write(manifestOut.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(DistributedArtifactManifest.ARTIFACT_PATH));
            zip.write(content);
            zip.closeEntry();
        }
        // The v1 manifest decoded from this bundle has projectRelativeRoot = ""
        DistributedArtifactManifest v1Manifest = new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V1,
                runId, projectId, "",
                sourceRepo, commit, Language.JAVA,
                "fixture-provider", "1.2.3", "worker-one",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE, WorkerNetworkPolicy.ALLOW, false,
                Instant.parse("2026-07-29T00:00:00Z"), Instant.parse("2026-07-29T00:00:01Z"),
                DistributedArtifactManifest.ARTIFACT_PATH,
                content.length, sha256);
        return new BundleAndManifest(bundlePath, v1Manifest);
    }

    private static IndexingExecutionRequest executionFor(
            Path registeredRoot, String scope, UUID runId, UUID projectId) {
        Path projectRoot = scope.isEmpty()
                ? registeredRoot
                : registeredRoot.resolve(Path.of(scope));
        return new IndexingExecutionRequest(
                runId, projectId,
                registeredRoot, projectRoot, Path.of(scope),
                new IndexerSelection(Language.JAVA, descriptor()),
                IndexingMode.FULL, List.of());
    }

    private static Worker stubWorker(BundleAndManifest bundle) {
        return new Worker() {
            @Override public String workerId() { return "worker-one"; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public boolean enforcesNetworkDeny() { return false; }
            @Override
            public WorkerResponse execute(WorkerRequest request) {
                return new WorkerResponse(bundle.bundle(), bundle.manifest());
            }
        };
    }

    private static DistributedArtifactBundleStore store(Path temp) throws Exception {
        return new DistributedArtifactBundleStore(
                temp.resolve("home"), new DistributedArtifactCachePolicy(16, 4096, 64));
    }

    private static IndexerDescriptor descriptor() {
        return new IndexerDescriptor(
                "fixture-provider", "1.2.3", "Fixture provider",
                Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED, 1, List.of());
    }

    private record BundleAndManifest(Path bundle, DistributedArtifactManifest manifest) {}
}
