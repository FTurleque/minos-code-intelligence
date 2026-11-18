package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.ConfinedFileOpener;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedArtifactSource;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.runtime.local.distributed.CountingBundleStores;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.runtime.local.distributed.DistributedArtifactCachePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * indexation-java-via-wsl › La voie WSL réutilise l'empreinte du projet connue de l'indexation, and
 * L'artefact de WSL est lu au plus deux fois entre l'acceptation du paquet et son retour.
 */
class WslIndexerExecutorTest {

    private static final String PROVIDER = "scip-java";
    private static final String VERSION = "0.13.2";
    private static final Instant STARTED = Instant.parse("2026-11-08T00:00:00Z");
    private static final String PROVIDED = "f".repeat(64);
    private static final String CAPTURED = "c".repeat(64);

    @TempDir Path temp;
    private Path project;
    private Path home;
    private final List<Path> opened = new ArrayList<>();
    private final AtomicInteger captures = new AtomicInteger();
    private DistributedArtifactBundleStore store;

    @BeforeEach
    void project() throws IOException {
        project = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(project.resolve("module-a/src"));
        Files.createDirectories(project.resolve("module-b/src"));
        home = temp.resolve("home");
        store = CountingBundleStores.countingStore(home, DistributedArtifactCachePolicy.DEFAULT, opened);
    }

    @Test
    void twoTargetsCarryingTheProjectFingerprintCaptureNothing() throws Exception {
        FakeWorker worker = new FakeWorker();
        WslIndexerExecutor executor = executor(worker, ConfinedFileOpener::openRegularFileNoFollow);
        UUID runId = UUID.randomUUID();

        executor.execute(request(runId, "module-a", Optional.of(PROVIDED)));
        executor.execute(request(runId, "module-b", Optional.of(PROVIDED)));

        assertEquals(0, captures.get(), "the lane does not hash the source tree");
        assertEquals(List.of(PROVIDED, PROVIDED),
                worker.requests.stream().map(WorkerRequest::projectFingerprint).toList());
        assertEquals(List.of(PROVIDED, PROVIDED), worker.manifests.stream()
                .map(manifest -> ((DistributedArtifactSource.LocalProject) manifest.source()).fingerprint())
                .toList());
    }

    @Test
    void twoTargetsWithoutTheProjectFingerprintCaptureItOncePerRun() throws Exception {
        FakeWorker worker = new FakeWorker();
        WslIndexerExecutor executor = executor(worker, ConfinedFileOpener::openRegularFileNoFollow);
        UUID runId = UUID.randomUUID();

        executor.execute(request(runId, "module-a", Optional.empty()));
        executor.execute(request(runId, "module-b", Optional.empty()));

        assertEquals(1, captures.get(), "one capture per run, whatever the number of targets");
        assertEquals(List.of(CAPTURED, CAPTURED),
                worker.requests.stream().map(WorkerRequest::projectFingerprint).toList());

        executor.execute(request(UUID.randomUUID(), "module-a", Optional.empty()));
        assertEquals(2, captures.get(), "another run captures again");
    }

    @Test
    void aManifestNamingAnotherFingerprintIsRefusedAndItsBundleDeleted() {
        FakeWorker worker = new FakeWorker();
        worker.fingerprintOverride = "e".repeat(64);
        WslIndexerExecutor executor = executor(worker, ConfinedFileOpener::openRegularFileNoFollow);
        IndexingExecutionRequest request = request(UUID.randomUUID(), "module-a", Optional.of(PROVIDED));

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertEquals("WSL artifact provenance does not match the indexing request", refusal.getMessage());
        assertFalse(Files.exists(worker.bundles.getFirst()), "the bundle is deleted");
        assertFalse(Files.exists(home.resolve("runs").resolve(request.runId().toString())),
                "nothing reaches the run directory");
    }

    @Test
    void theNominalPathReadsTheBundleOnceAndTheCacheEntryOnce() throws Exception {
        FakeWorker worker = new FakeWorker();
        WslIndexerExecutor executor = executor(worker, this::countedOpen);

        IndexingArtifact artifact = executor.execute(request(UUID.randomUUID(), "module-a", Optional.of(PROVIDED)));

        Path bundle = worker.bundles.getFirst().toAbsolutePath().normalize();
        Path cacheRoot = home.resolve("distributed-artifacts").toAbsolutePath().normalize();
        List<Path> afterAcceptance = opened.subList(worker.openedBeforeResponse, opened.size());
        assertEquals(1, afterAcceptance.stream().filter(bundle::equals).count(), "bundle: " + afterAcceptance);
        assertEquals(0, afterAcceptance.stream().filter(path -> path.startsWith(cacheRoot)
                && path.getParent().getFileName().toString().startsWith(".accept-")).count(),
                "extracted artifact: " + afterAcceptance);
        assertEquals(1, afterAcceptance.stream().filter(path -> path.startsWith(cacheRoot)
                && !path.getParent().getFileName().toString().startsWith(".accept-")).count(),
                "cache entry: " + afterAcceptance);
        assertEquals(0, afterAcceptance.stream().filter(path -> path.startsWith(home.resolve("runs"))).count(),
                "copy under the run: " + afterAcceptance);
        assertEquals(2, afterAcceptance.size(), "at most two reads of the artifact: " + afterAcceptance);
        assertArrayEquals(worker.payload.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(artifact.finalArtifact()));
    }

    @Test
    void aCacheEntryAlteredBeforeTheCopyFailsTheTargetWithoutCopy() throws Exception {
        FakeWorker worker = new FakeWorker();
        WslIndexerExecutor executor = executor(worker, artifact -> {
            byte[] content = Files.readAllBytes(artifact);
            content[0] = (byte) (content[0] ^ 1);
            Files.write(artifact, content);
            return ConfinedFileOpener.openRegularFileNoFollow(artifact);
        });
        IndexingExecutionRequest request = request(UUID.randomUUID(), "module-a", Optional.of(PROVIDED));

        IOException refusal = assertThrows(IOException.class, () -> executor.execute(request));

        assertEquals("WSL artifact copy does not match its verified checksum", refusal.getMessage());
        assertFalse(Files.exists(worker.bundles.getFirst()), "the bundle is deleted");
        try (var copies = Files.walk(home.resolve("runs").resolve(request.runId().toString()))) {
            assertTrue(copies.noneMatch(path -> path.getFileName().toString().equals("index.scip")),
                    "the copy is deleted");
        }
    }

    private java.io.InputStream countedOpen(Path artifact) throws IOException {
        opened.add(artifact.toAbsolutePath().normalize());
        return ConfinedFileOpener.openRegularFileNoFollow(artifact);
    }

    private WslIndexerExecutor executor(Worker worker, WslIndexerExecutor.ArtifactOpener opener) {
        return new WslIndexerExecutor(PROVIDER, VERSION, worker, store, root -> {
            captures.incrementAndGet();
            return CAPTURED;
        }, opener, home);
    }

    private IndexingExecutionRequest request(UUID runId, String scope, Optional<String> fingerprint) {
        IndexerDescriptor descriptor = new IndexerDescriptor(PROVIDER, VERSION, PROVIDER, Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 1,
                List.of());
        return new IndexingExecutionRequest(runId, UUID.nameUUIDFromBytes(project.toString().getBytes(
                StandardCharsets.UTF_8)), project, project.resolve(scope), Path.of(scope),
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of(), Optional.empty(),
                fingerprint);
    }

    /** A distribution that answers with a bundle faithful to the request. */
    private final class FakeWorker implements Worker {
        private final List<Path> bundles = new ArrayList<>();
        private final List<WorkerRequest> requests = new ArrayList<>();
        private final List<DistributedArtifactManifest> manifests = new ArrayList<>();
        private final String payload = "scip-" + UUID.randomUUID();
        private String fingerprintOverride;
        private int openedBeforeResponse;

        @Override public String workerId() { return "wsl:Ubuntu"; }
        @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
        @Override public boolean enforcesNetworkDeny() { return true; }

        @Override
        public WorkerResponse execute(WorkerRequest request) throws Exception {
            requests.add(request);
            String name = "bundle-" + UUID.randomUUID();
            Path artifact = Files.writeString(temp.resolve(name + ".scip"), payload);
            String fingerprint = fingerprintOverride != null ? fingerprintOverride : request.projectFingerprint();
            DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                    DistributedArtifactManifest.FORMAT_V3, request.execution().runId(),
                    request.execution().projectId(),
                    request.execution().projectRelativeRoot().toString().replace('\\', '/'),
                    new DistributedArtifactSource.LocalProject(fingerprint),
                    request.execution().selection().language(), request.execution().selection().indexer().id(),
                    request.providerVersion(), workerId(), WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                    WorkerNetworkPolicy.DENY, true, STARTED, STARTED.plusSeconds(1),
                    DistributedArtifactManifest.ARTIFACT_PATH, Files.size(artifact),
                    DistributedArtifactBundleStore.sha256(artifact));
            Path bundle = store.createBundle(temp.resolve(name + ".zip"), manifest, artifact);
            bundles.add(bundle);
            manifests.add(manifest);
            openedBeforeResponse = opened.size();
            return new WorkerResponse(bundle, manifest);
        }
    }
}
