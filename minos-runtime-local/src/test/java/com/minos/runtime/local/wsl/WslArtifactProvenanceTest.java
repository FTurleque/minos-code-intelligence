package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.incremental.ProjectFingerprintService;
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
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * indexation-java-via-wsl › L'artefact reçu de WSL est vérifié avant tout import: the WSL executor imports only a
 * bundle accepted by the verified store whose v3 provenance names exactly the request, and deletes every bundle.
 */
class WslArtifactProvenanceTest {

    private static final String PROVIDER = "scip-java";
    private static final String VERSION = "0.13.2";
    private static final Instant STARTED = Instant.parse("2026-11-08T00:00:00Z");

    @TempDir Path temp;
    private Path project;
    private DistributedArtifactBundleStore store;
    private final ProjectFingerprintService fingerprints = new ProjectFingerprintService();

    @BeforeEach
    void project() throws IOException {
        project = Files.createDirectories(temp.resolve("project/src/main/java")).getParent().getParent().getParent();
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/main/java/App.java"), "class App {}");
        store = new DistributedArtifactBundleStore(temp.resolve("home"));
    }

    @Test
    void aFaithfulV3BundleIsImportedFromTheVerifiedStoreAndItsEnvelopeIsDeleted() throws Exception {
        FakeWorker worker = new FakeWorker(draft -> { });
        WslIndexerExecutor executor = executor(worker);

        IndexingArtifact artifact = executor.execute(request(PROVIDER));

        assertEquals(PROVIDER, artifact.indexerId());
        assertEquals(Path.of(""), artifact.projectRelativeRoot());
        assertTrue(executor.artifactsLiveInRunDirectory());
        assertTrue(artifact.finalArtifact().startsWith(temp.resolve("home").resolve("runs")),
                "the verified artifact is copied under the run directory");
        assertArrayEquals(worker.payload.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(artifact.finalArtifact()));
        assertFalse(Files.exists(worker.bundles.getFirst()), "the transport envelope is consumed");
        assertEquals(fingerprints.capture(project).projectSha256(), worker.requests.getFirst().projectFingerprint());
        assertTrue(worker.requests.getFirst().local());
    }

    @Test
    void everyProvenanceFieldThatDiffersFromTheRequestRejectsTheBundle() {
        Map<String, Consumer<Draft>> forgeries = new LinkedHashMap<>();
        forgeries.put("runId", draft -> draft.runId = UUID.randomUUID());
        forgeries.put("projectId (another project)", draft -> draft.projectId = UUID.randomUUID());
        forgeries.put("projectRelativeRoot", draft -> draft.scope = "module-b");
        forgeries.put("projectFingerprint", draft -> draft.fingerprint = "e".repeat(64));
        forgeries.put("language", draft -> draft.language = Language.PYTHON);
        forgeries.put("providerId", draft -> draft.providerId = "scip-python");
        forgeries.put("providerVersion", draft -> draft.providerVersion = "9.9.9");
        forgeries.put("workerId", draft -> draft.workerId = "wsl:Other");

        for (Map.Entry<String, Consumer<Draft>> forgery : forgeries.entrySet()) {
            FakeWorker worker = new FakeWorker(forgery.getValue());
            WslIndexerExecutor executor = executor(worker);
            IndexingExecutionRequest request = request(PROVIDER);

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> executor.execute(request), forgery.getKey());

            assertEquals("WSL artifact provenance does not match the indexing request", refusal.getMessage(),
                    forgery.getKey());
            assertFalse(Files.exists(worker.bundles.getFirst()), "rejected envelope removed: " + forgery.getKey());
            assertFalse(Files.exists(temp.resolve("home").resolve("runs").resolve(request.runId().toString())),
                    "nothing reaches the run directory: " + forgery.getKey());
        }
    }

    @Test
    void remoteManifestsV1AndV2AreRefusedByTheWslRoute() {
        for (String format : List.of(DistributedArtifactManifest.FORMAT_V1, DistributedArtifactManifest.FORMAT_V2)) {
            FakeWorker worker = new FakeWorker(draft -> {
                draft.format = format;
                draft.fingerprint = null;
                draft.repository = "https://github.com/acme/demo";
                draft.commit = "a".repeat(40);
            });
            WslIndexerExecutor executor = executor(worker);
            IndexingExecutionRequest request = request(PROVIDER);

            IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

            assertEquals("the WSL route accepts only artifact manifest format v3", refusal.getMessage(), format);
            assertFalse(Files.exists(worker.bundles.getFirst()), format);
        }
    }

    @Test
    void aBundleWhoseArtifactDoesNotMatchItsManifestChecksumIsRejectedAndDeleted() {
        FakeWorker worker = new FakeWorker(draft -> { });
        worker.tamper = true;
        WslIndexerExecutor executor = executor(worker);
        IndexingExecutionRequest request = request(PROVIDER);

        IOException refusal = assertThrows(IOException.class, () -> executor.execute(request));

        assertEquals("transported artifact checksum does not match its manifest", refusal.getMessage());
        assertFalse(Files.exists(worker.bundles.getFirst()), "the altered envelope is removed");
    }

    @Test
    void aResponseManifestThatDiffersFromTheTransportedOneIsRejected() {
        FakeWorker worker = new FakeWorker(draft -> { });
        worker.announced = draft -> draft.providerVersion = "9.9.9";
        WslIndexerExecutor executor = executor(worker);
        IndexingExecutionRequest request = request(PROVIDER);

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertEquals("worker response manifest differs from the transported manifest", refusal.getMessage());
        assertFalse(Files.exists(worker.bundles.getFirst()));
    }

    @Test
    void anotherProviderIsRefusedBeforeTheWorkerRuns() {
        FakeWorker worker = new FakeWorker(draft -> { });
        WslIndexerExecutor executor = executor(worker);
        IndexingExecutionRequest request = request("scip-typescript");

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request));
        assertTrue(worker.requests.isEmpty());
        Path home = temp.resolve("home");
        assertThrows(IllegalArgumentException.class,
                () -> new WslIndexerExecutor(" ", VERSION, worker, store, fingerprints, home));
    }

    private WslIndexerExecutor executor(Worker worker) {
        return new WslIndexerExecutor(PROVIDER, VERSION, worker, store, fingerprints, temp.resolve("home"));
    }

    private IndexingExecutionRequest request(String providerId) {
        IndexerDescriptor descriptor = new IndexerDescriptor(providerId, VERSION, providerId, Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 1,
                List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project, project, Path.of(""),
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of(), java.util.Optional.empty());
    }

    /** Provenance of a manifest, editable field by field before it is built. */
    private static final class Draft {
        private String format = DistributedArtifactManifest.FORMAT_V3;
        private UUID runId;
        private UUID projectId;
        private String scope;
        private String repository;
        private String commit;
        private String fingerprint;
        private Language language;
        private String providerId;
        private String providerVersion;
        private String workerId;

        private DistributedArtifactManifest build(Path artifact) throws IOException {
            return new DistributedArtifactManifest(format, runId, projectId, scope,
                    fingerprint != null ? new DistributedArtifactSource.LocalProject(fingerprint)
                            : new DistributedArtifactSource.RemoteRevision(repository, commit),
                    language, providerId, providerVersion, workerId, WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                    WorkerNetworkPolicy.DENY, true, STARTED, STARTED.plusSeconds(1),
                    DistributedArtifactManifest.ARTIFACT_PATH, Files.size(artifact),
                    DistributedArtifactBundleStore.sha256(artifact));
        }
    }

    /** A distribution that answers with a bundle faithful to the request, after the forgery of the test. */
    private final class FakeWorker implements Worker {
        private final Consumer<Draft> transported;
        private final List<Path> bundles = new ArrayList<>();
        private final List<WorkerRequest> requests = new ArrayList<>();
        private final String payload = "scip-" + UUID.randomUUID();
        private Consumer<Draft> announced;
        private boolean tamper;

        private FakeWorker(Consumer<Draft> transported) {
            this.transported = transported;
        }

        @Override public String workerId() { return "wsl:Ubuntu"; }
        @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
        @Override public boolean enforcesNetworkDeny() { return true; }

        @Override
        public WorkerResponse execute(WorkerRequest request) throws Exception {
            requests.add(request);
            String name = "bundle-" + UUID.randomUUID();
            Path artifact = Files.writeString(temp.resolve(name + ".scip"), payload);
            Draft draft = new Draft();
            draft.runId = request.execution().runId();
            draft.projectId = request.execution().projectId();
            draft.scope = "";
            draft.fingerprint = request.projectFingerprint();
            draft.language = request.execution().selection().language();
            draft.providerId = request.execution().selection().indexer().id();
            draft.providerVersion = request.providerVersion();
            draft.workerId = workerId();
            transported.accept(draft);
            DistributedArtifactManifest manifest = draft.build(artifact);
            Path bundle = store.createBundle(temp.resolve(name + ".zip"), manifest, artifact);
            if (tamper) {
                bundle = alterArtifact(bundle);
            }
            bundles.add(bundle);
            if (announced == null) return new WorkerResponse(bundle, manifest);
            announced.accept(draft);
            return new WorkerResponse(bundle, draft.build(artifact));
        }

        /** Rewrites the bundle with the same manifest and another artifact of the same size. */
        private Path alterArtifact(Path bundle) throws IOException {
            Path altered = temp.resolve("altered-" + UUID.randomUUID() + ".zip");
            try (ZipInputStream input = new ZipInputStream(Files.newInputStream(bundle));
                 OutputStream file = Files.newOutputStream(altered);
                 ZipOutputStream output = new ZipOutputStream(file)) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    byte[] content = input.readAllBytes();
                    if (DistributedArtifactManifest.ARTIFACT_PATH.equals(entry.getName())) {
                        content[0] = (byte) (content[0] ^ 1);
                    }
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    output.write(content);
                    output.closeEntry();
                }
            }
            Files.delete(bundle);
            return altered;
        }
    }
}
