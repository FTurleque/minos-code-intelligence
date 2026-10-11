package com.minos.runtime.local.distributed;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le contrat « artefact vérifié » de {@link DistributedIndexerExecutor} : un artefact dont la provenance
 * ne correspond pas exactement à la requête est refusé, libéré et son enveloppe supprimée.
 */
class DistributedIndexerExecutorProvenanceTest {

    private static final String PROVIDER = "fixture-provider";
    private static final String PROVIDER_VERSION = "1.2.3";
    private static final String REPOSITORY = "https://github.com/acme/demo";
    private static final String COMMIT = "a".repeat(40);
    private static final Instant STARTED = Instant.parse("2026-08-13T00:00:00Z");

    @TempDir
    Path temp;

    private Path repositoryRoot;
    private Path registeredRoot;
    private Path module;
    private DistributedArtifactBundleStore store;
    private final UUID runId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void materialize() throws IOException {
        repositoryRoot = Files.createDirectories(temp.resolve("repository"));
        registeredRoot = Files.createDirectories(repositoryRoot.resolve("project"));
        module = Files.createDirectories(registeredRoot.resolve("module-a"));
        store = new DistributedArtifactBundleStore(temp.resolve("home"));
    }

    @Test
    void aRootScopeArtifactUnderAnEnforcedDenyPolicyIsAcceptedAndReleasedOnClose() throws Exception {
        FixtureWorker worker = new FixtureWorker(true, draft -> { });
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.DENY, worker);

        IndexingArtifact artifact = executor.execute(execution(PROVIDER, registeredRoot, registeredRoot, ""));

        assertEquals(PROVIDER, executor.indexerId());
        assertEquals(Path.of(""), artifact.projectRelativeRoot(), "the root scope stays the empty relative path");
        assertEquals(1, executor.verifiedArtifacts().size());
        assertTrue(executor.verifiedArtifact().orElseThrow().manifest().networkDenyEnforced());
        assertFalse(Files.exists(worker.bundles.getFirst()),
                "the transport envelope is consumed once its artifact is in the verified cache");

        executor.close();

        assertTrue(executor.verifiedArtifacts().isEmpty(), "close releases every retained artifact");
        assertTrue(executor.verifiedArtifact().isEmpty());
    }

    @Test
    void aRequestBoundToTheExactMaterializedScopeIsAccepted() throws Exception {
        FixtureWorker worker = new FixtureWorker(false, draft -> { });
        DistributedIndexerExecutor executor = executor(module, WorkerNetworkPolicy.ALLOW, worker);

        IndexingArtifact artifact = executor.execute(execution(PROVIDER, registeredRoot, module, "module-a"));

        assertEquals(Path.of("module-a"), artifact.projectRelativeRoot());
        assertEquals("module-a", executor.verifiedArtifact().orElseThrow().manifest().projectRelativeRoot());
        executor.close();
    }

    @Test
    void everyProvenanceFieldThatDiffersFromTheRequestRejectsTheArtifact() throws Exception {
        Map<String, Consumer<Draft>> forgeries = new LinkedHashMap<>();
        forgeries.put("runId", draft -> draft.runId = UUID.randomUUID());
        forgeries.put("projectId", draft -> draft.projectId = UUID.randomUUID());
        forgeries.put("projectRelativeRoot", draft -> draft.scope = "module-b");
        forgeries.put("sourceRepository", draft -> draft.repository = "https://github.com/acme/other");
        forgeries.put("sourceCommit", draft -> draft.commit = "b".repeat(40));
        forgeries.put("language", draft -> draft.language = Language.PYTHON);
        forgeries.put("providerId", draft -> draft.providerId = "other-provider");
        forgeries.put("providerVersion", draft -> draft.providerVersion = "9.9.9");
        forgeries.put("workerId", draft -> draft.workerId = "other-worker");
        forgeries.put("networkPolicy", draft -> {
            draft.networkPolicy = WorkerNetworkPolicy.DENY;
            draft.networkDenyEnforced = true;
        });
        forgeries.put("networkDenyEnforced", draft -> draft.networkDenyEnforced = true);

        for (Map.Entry<String, Consumer<Draft>> forgery : forgeries.entrySet()) {
            FixtureWorker worker = new FixtureWorker(false, forgery.getValue());
            DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.ALLOW, worker);
            IndexingExecutionRequest request = execution(PROVIDER, registeredRoot, module, "module-a");

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> executor.execute(request), forgery.getKey());

            assertEquals("transported artifact provenance does not match the indexing request",
                    refusal.getMessage(), forgery.getKey());
            assertTrue(executor.verifiedArtifacts().isEmpty(),
                    "a forged " + forgery.getKey() + " must not leave a retained artifact");
            assertTrue(executor.verifiedArtifact().isEmpty(), forgery.getKey());
            assertFalse(Files.exists(worker.bundles.getFirst()),
                    "the rejected envelope must be removed (" + forgery.getKey() + ")");
            executor.close();
        }
    }

    @Test
    void aDenyManifestIsRejectedWhenTheWorkerDoesNotEnforceTheDenial() throws Exception {
        // A DENY manifest necessarily claims an enforced boundary; this worker declares it enforces none.
        FixtureWorker worker = new FixtureWorker(false, draft -> draft.networkDenyEnforced = true);
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.DENY, worker);
        IndexingExecutionRequest request = execution(PROVIDER, registeredRoot, module, "module-a");

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertEquals("transported artifact provenance does not match the indexing request", refusal.getMessage());
        assertTrue(executor.verifiedArtifacts().isEmpty(),
                "a denial the worker cannot enforce must never be recorded as enforced provenance");
    }

    @Test
    void anArtifactProducedUnderAllowIsRejectedUnderADenyPolicy() throws Exception {
        // The worker claims to enforce DENY, so the request expects an enforced boundary; the manifest it
        // transports was produced under ALLOW.
        FixtureWorker worker = new FixtureWorker(true, draft -> {
            draft.networkPolicy = WorkerNetworkPolicy.ALLOW;
            draft.networkDenyEnforced = false;
        });
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.DENY, worker);
        IndexingExecutionRequest request = execution(PROVIDER, registeredRoot, module, "module-a");

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertEquals("transported artifact provenance does not match the indexing request", refusal.getMessage());
        assertTrue(executor.verifiedArtifacts().isEmpty());
    }

    @Test
    void aManifestWithoutScopeProvenanceIsRejectedEvenForTheRootScope() throws Exception {
        FixtureWorker worker = new FixtureWorker(false, draft -> draft.format = DistributedArtifactManifest.FORMAT_V1);
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.ALLOW, worker);
        IndexingExecutionRequest request = execution(PROVIDER, registeredRoot, registeredRoot, "");

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertEquals("verified distributed artifact provenance requires scope-aware manifest format v2",
                refusal.getMessage());
        assertTrue(executor.verifiedArtifacts().isEmpty());
        assertFalse(Files.exists(worker.bundles.getFirst()));
    }

    @Test
    void aResponseManifestThatDiffersFromTheTransportedOneIsRejected() throws Exception {
        FixtureWorker worker = new FixtureWorker(false, draft -> { });
        worker.announced = draft -> draft.providerVersion = "9.9.9";
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.ALLOW, worker);
        IndexingExecutionRequest request = execution(PROVIDER, registeredRoot, module, "module-a");

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertEquals("worker response manifest differs from the transported manifest", refusal.getMessage());
        assertTrue(executor.verifiedArtifacts().isEmpty(),
                "the artifact accepted by the store must be released when the response is refused");
        assertFalse(Files.exists(worker.bundles.getFirst()));
    }

    @Test
    void aRequestForAnotherProviderIsRefusedBeforeTheWorkerRuns() throws Exception {
        FixtureWorker worker = new FixtureWorker(false, draft -> { });
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.ALLOW, worker);
        IndexingExecutionRequest request = execution("other-provider", registeredRoot, module, "module-a");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request));

        assertEquals("distributed executor selected another provider", refusal.getMessage());
        assertTrue(worker.bundles.isEmpty(), "no worker may run for a provider this executor does not own");
    }

    @Test
    void aRegisteredRootOutsideTheMaterializedRepositoryIsRefusedBeforeTheWorkerRuns() throws Exception {
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
        FixtureWorker worker = new FixtureWorker(false, draft -> { });
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.ALLOW, worker);
        IndexingExecutionRequest request = execution(PROVIDER, elsewhere, elsewhere, "");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request));

        assertEquals("distributed execution scope escapes the materialized repository", refusal.getMessage());
        assertTrue(worker.bundles.isEmpty());
    }

    @Test
    void aRequestForASiblingProjectOfTheMaterializedOneIsRefusedBeforeTheWorkerRuns() throws Exception {
        Path sibling = Files.createDirectories(repositoryRoot.resolve("sibling"));
        FixtureWorker worker = new FixtureWorker(false, draft -> { });
        DistributedIndexerExecutor executor = executor(registeredRoot, WorkerNetworkPolicy.ALLOW, worker);
        IndexingExecutionRequest request = execution(PROVIDER, sibling, sibling, "");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request));

        assertEquals("distributed execution request is not bound to the materialized project root or exact scope",
                refusal.getMessage());
        assertTrue(worker.bundles.isEmpty());
    }

    @Test
    void anExecutorCannotBeBuiltWithoutItsIdentityOrCollaborators() {
        RemoteMaterialization source = source(registeredRoot);
        FixtureWorker worker = new FixtureWorker(false, draft -> { });

        IllegalArgumentException identity = assertThrows(IllegalArgumentException.class,
                () -> new DistributedIndexerExecutor(
                        " ", PROVIDER_VERSION, source, WorkerNetworkPolicy.ALLOW, worker, store));
        IllegalArgumentException version = assertThrows(IllegalArgumentException.class,
                () -> new DistributedIndexerExecutor(
                        PROVIDER, "", source, WorkerNetworkPolicy.ALLOW, worker, store));
        assertEquals("indexerId must not be blank", identity.getMessage());
        assertEquals("providerVersion must not be blank", version.getMessage());
        assertThrows(NullPointerException.class, () -> new DistributedIndexerExecutor(
                PROVIDER, PROVIDER_VERSION, null, WorkerNetworkPolicy.ALLOW, worker, store));
        assertThrows(NullPointerException.class, () -> new DistributedIndexerExecutor(
                PROVIDER, PROVIDER_VERSION, source, null, worker, store));
        assertThrows(NullPointerException.class, () -> new DistributedIndexerExecutor(
                PROVIDER, PROVIDER_VERSION, source, WorkerNetworkPolicy.ALLOW, null, store));
        assertThrows(NullPointerException.class, () -> new DistributedIndexerExecutor(
                PROVIDER, PROVIDER_VERSION, source, WorkerNetworkPolicy.ALLOW, worker, null));
    }

    private DistributedIndexerExecutor executor(Path materializedRoot, WorkerNetworkPolicy policy, Worker worker) {
        return new DistributedIndexerExecutor(
                PROVIDER, PROVIDER_VERSION, source(materializedRoot), policy, worker, store);
    }

    private RemoteMaterialization source(Path materializedRoot) {
        RemoteRepositoryRequest request = RemoteRepositoryRequest.of(REPOSITORY, "main", COMMIT, "project", null);
        return new RemoteMaterialization(request, repositoryRoot, materializedRoot, "c".repeat(64), false, STARTED);
    }

    private IndexingExecutionRequest execution(String providerId, Path registered, Path executionRoot, String scope) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                providerId,
                PROVIDER_VERSION,
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        return new IndexingExecutionRequest(
                runId,
                projectId,
                registered,
                executionRoot,
                Path.of(scope),
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }

    /** Champs de provenance d'un manifeste, modifiables un à un avant sa construction. */
    private static final class Draft {
        private String format = DistributedArtifactManifest.FORMAT_V2;
        private UUID runId;
        private UUID projectId;
        private String scope;
        private String repository;
        private String commit;
        private Language language;
        private String providerId;
        private String providerVersion;
        private String workerId;
        private WorkerNetworkPolicy networkPolicy;
        private boolean networkDenyEnforced;

        private DistributedArtifactManifest build(Path artifact) throws IOException {
            return new DistributedArtifactManifest(
                    format,
                    runId,
                    projectId,
                    scope,
                    repository,
                    commit,
                    language,
                    providerId,
                    providerVersion,
                    workerId,
                    WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                    networkPolicy,
                    networkDenyEnforced,
                    STARTED,
                    STARTED.plusSeconds(1),
                    DistributedArtifactManifest.ARTIFACT_PATH,
                    Files.size(artifact),
                    DistributedArtifactBundleStore.sha256(artifact));
        }
    }

    /** Worker qui transporte un manifeste fidèle à la requête, après la falsification demandée par le test. */
    private final class FixtureWorker implements Worker {
        private final boolean enforcesNetworkDeny;
        private final Consumer<Draft> transported;
        private final List<Path> bundles = new ArrayList<>();
        private Consumer<Draft> announced;

        private FixtureWorker(boolean enforcesNetworkDeny, Consumer<Draft> transported) {
            this.enforcesNetworkDeny = enforcesNetworkDeny;
            this.transported = transported;
        }

        @Override public String workerId() { return "fixture-worker"; }
        @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
        @Override public boolean enforcesNetworkDeny() { return enforcesNetworkDeny; }

        @Override
        public WorkerResponse execute(WorkerRequest request) throws Exception {
            String name = "bundle-" + UUID.randomUUID();
            Path artifact = Files.writeString(temp.resolve(name + ".scip"), name);
            Draft draft = faithful(request);
            transported.accept(draft);
            DistributedArtifactManifest manifest = draft.build(artifact);
            Path bundle = store.createBundle(temp.resolve(name + ".zip"), manifest, artifact);
            bundles.add(bundle);
            if (announced == null) return new WorkerResponse(bundle, manifest);
            announced.accept(draft);
            return new WorkerResponse(bundle, draft.build(artifact));
        }

        private Draft faithful(WorkerRequest request) {
            Draft draft = new Draft();
            draft.runId = request.execution().runId();
            draft.projectId = request.execution().projectId();
            draft.scope = request.execution().projectRelativeRoot().toString().replace('\\', '/');
            draft.repository = request.sourceRepository();
            draft.commit = request.sourceCommit();
            draft.language = request.execution().selection().language();
            draft.providerId = request.execution().selection().indexer().id();
            draft.providerVersion = request.providerVersion();
            draft.workerId = workerId();
            draft.networkPolicy = request.networkPolicy();
            draft.networkDenyEnforced = request.networkPolicy() == WorkerNetworkPolicy.DENY && enforcesNetworkDeny;
            return draft;
        }
    }
}
