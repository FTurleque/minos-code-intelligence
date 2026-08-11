package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalIsolatedIndexWorkerTest {

    @Test
    void qualifiedSandboxWithAllowExecutesInCopiedWorkspaceAndPreservesArtifactEvidence(@TempDir Path temp)
            throws Exception {
        Fixture fixture = fixture(temp);
        AtomicReference<Path> delegateRoot = new AtomicReference<>();
        IndexerExecutor delegate = delegate(temp, delegateRoot);
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        LocalIsolatedIndexWorker worker = worker(
                "worker-one", temp.resolve("home"), delegate, store,
                qualifiedBackend(WorkerNetworkPolicy.ALLOW));
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.ALLOW, worker, store);

        IndexingArtifact artifact = executor.execute(fixture.execution());

        assertEquals(Language.JAVA, artifact.language());
        assertEquals("fixture-provider", artifact.indexerId());
        assertTrue(Files.isRegularFile(artifact.finalArtifact()));
        assertEquals("fixture-os-sandbox", worker.sandboxBackendId());
        assertNotEquals(
                fixture.projectRoot().toAbsolutePath().normalize(),
                delegateRoot.get().toAbsolutePath().normalize());
        assertFalse(Files.exists(delegateRoot.get()));
        var verified = executor.verifiedArtifact().orElseThrow();
        assertEquals(fixture.request().expectedCommit(), verified.manifest().sourceCommit());
        assertEquals("PROCESS_EPHEMERAL_WORKSPACE", verified.manifest().isolation().name());
        assertEquals(WorkerNetworkPolicy.ALLOW, verified.manifest().networkPolicy());
        assertFalse(verified.manifest().networkDenyEnforced());
        try (var paths = Files.list(temp.resolve("home/distributed-workers"))) {
            assertTrue(paths.findAny().isEmpty(),
                    "transport envelopes and worker directories must be removed");
        }
    }

    @Test
    void nativeWorkerFailsClosedWhenNetworkDenyIsRequired(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        LocalIsolatedIndexWorker worker = nativeWorker(
                "worker-one", temp.resolve("home"),
                delegate(temp, new AtomicReference<>()), store);
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.DENY, worker, store);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(fixture.execution()));
        assertTrue(failure.getMessage().contains("not qualified for untrusted remote code"));
        assertTrue(executor.verifiedArtifact().isEmpty());
    }

    @Test
    void nativeWorkerAlsoFailsClosedWhenNetworkIsAllowed(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        LocalIsolatedIndexWorker worker = nativeWorker(
                "worker-one", temp.resolve("home"),
                delegate(temp, new AtomicReference<>()), store);
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.ALLOW, worker, store);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(fixture.execution()));

        assertTrue(failure.getMessage().contains("not qualified for untrusted remote code"));
        assertTrue(executor.verifiedArtifact().isEmpty());
    }

    @Test
    void denyIsAcceptedOnlyThroughAnOsEnforcedBackend(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        AtomicReference<Path> delegateRoot = new AtomicReference<>();
        IndexerExecutor delegate = delegate(temp, delegateRoot);
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        WorkerSandboxBackend qualifiedBackend = new WorkerSandboxBackend() {
            @Override public String id() { return "fixture-os-sandbox"; }
            @Override public WorkerIsolation isolation() {
                return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
            }
            @Override public NetworkGuarantee networkGuarantee() {
                return NetworkGuarantee.OS_ENFORCED;
            }
            @Override
            public WorkerSandboxQualification qualification() {
                return new WorkerSandboxQualification(
                        id(),
                        isolation(),
                        networkGuarantee(),
                        WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                        WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                        Map.of(
                                WorkerSandboxQualification.currentPlatform(),
                                WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                        List.of("TEST_FIXTURE_OS_QUALIFIED"));
            }
            @Override
            public IndexingArtifact execute(
                    IndexerExecutor selected,
                    IndexingExecutionRequest request,
                    WorkerNetworkPolicy networkPolicy
            ) throws Exception {
                assertEquals(WorkerNetworkPolicy.DENY, networkPolicy);
                return selected.execute(request);
            }
        };
        LocalIsolatedIndexWorker worker = new LocalIsolatedIndexWorker(
                "worker-qualified",
                temp.resolve("home"),
                delegate,
                store,
                qualifiedBackend,
                100,
                1024 * 1024,
                Clock.systemUTC());
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.DENY, worker, store);

        executor.execute(fixture.execution());

        assertEquals("fixture-os-sandbox", worker.sandboxBackendId());
        assertTrue(executor.verifiedArtifact().orElseThrow().manifest().networkDenyEnforced());
        assertFalse(Files.exists(delegateRoot.get()));
    }

    @Test
    void strongestAvailableSelectorNeverOverstatesNetworkGuarantee(@TempDir Path temp) {
        WorkerSandboxBackend backend = WorkerSandboxBackends.strongestAvailable(temp.resolve("home"));
        if (backend.networkGuarantee() == WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            assertTrue(backend.qualification().qualifiedForCurrentPlatform());
            assertTrue(backend.qualification().sandboxClaimPermitted());
        } else {
            assertEquals("native-process-ephemeral-workspace-v1", backend.id());
            assertFalse(backend.enforcesNetworkDeny());
        }
    }

    @Test
    void coordinatorRejectsCryptographicallyValidArtifactWithWrongProvenance(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        Path sourceArtifact = Files.writeString(temp.resolve("malicious.scip"), "malicious");
        DistributedArtifactManifest wrong = new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V1,
                fixture.execution().runId(),
                fixture.execution().projectId(),
                fixture.request().canonicalRepositoryUri(),
                "b".repeat(40),
                Language.JAVA,
                "fixture-provider",
                "1.2.3",
                "worker-one",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerNetworkPolicy.ALLOW,
                false,
                Instant.parse("2026-07-29T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:01Z"),
                DistributedArtifactManifest.ARTIFACT_PATH,
                Files.size(sourceArtifact),
                DistributedArtifactBundleStore.sha256(sourceArtifact));
        Path bundle = store.createBundle(temp.resolve("wrong.zip"), wrong, sourceArtifact);
        Worker malicious = new Worker() {
            @Override public String workerId() { return "worker-one"; }
            @Override public WorkerIsolation isolation() {
                return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
            }
            @Override public boolean enforcesNetworkDeny() { return false; }
            @Override
            public WorkerResponse execute(
                    com.minos.remote.DistributedIndexing.WorkerRequest request
            ) {
                return new WorkerResponse(bundle, wrong);
            }
        };
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.ALLOW, malicious, store);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(fixture.execution()));
        assertTrue(failure.getMessage().contains("provenance"));
        assertFalse(Files.exists(bundle), "rejected transport envelopes must be removed");
    }

    @Test
    void copiedWorkspaceUsesSharedIgnoreRulesIncludingNegationAndHardIgnores(@TempDir Path temp)
            throws Exception {
        Fixture fixture = fixture(temp);
        Path project = fixture.projectRoot();
        Files.writeString(project.resolve(".minosignore"),
                ".env\nprivate/**\nignored/**\n!ignored/keep.txt\n");
        Files.writeString(project.resolve(".env"), "SECRET=hidden");
        Files.createDirectories(project.resolve("private/nested"));
        Files.writeString(project.resolve("private/nested/credentials.json"), "hidden");
        Files.createDirectories(project.resolve("ignored"));
        Files.writeString(project.resolve("ignored/drop.txt"), "hidden");
        Files.writeString(project.resolve("ignored/keep.txt"), "visible");
        Files.createDirectories(project.resolve("target/classes"));
        Files.writeString(project.resolve("target/classes/secret.pem"), "hidden");
        Files.writeString(project.resolve("normal.txt"), "visible");

        AtomicReference<Path> delegateRoot = new AtomicReference<>();
        IndexerExecutor delegate = new IndexerExecutor() {
            @Override public String indexerId() { return "fixture-provider"; }
            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                Path workspace = request.projectRoot();
                delegateRoot.set(workspace);
                assertFalse(Files.exists(workspace.resolve(".env")));
                assertFalse(Files.exists(workspace.resolve("private/nested/credentials.json")));
                assertFalse(Files.exists(workspace.resolve("ignored/drop.txt")));
                assertTrue(Files.isRegularFile(workspace.resolve("ignored/keep.txt")));
                assertFalse(Files.exists(workspace.resolve(".git")));
                assertFalse(Files.exists(workspace.resolve("target")));
                assertTrue(Files.isRegularFile(workspace.resolve("normal.txt")));
                Path output = Files.writeString(temp.resolve("ignored-rules.scip"), "valid-scip-fixture");
                return new IndexingArtifact(Language.JAVA, indexerId(), output);
            }
        };
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        LocalIsolatedIndexWorker worker = worker(
                "worker-ignore", temp.resolve("home"), delegate, store,
                qualifiedBackend(WorkerNetworkPolicy.ALLOW));
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.ALLOW, worker, store);

        executor.execute(fixture.execution());

        assertFalse(Files.exists(delegateRoot.get()), "ephemeral workspace must be cleaned");
    }

    @Test
    void copiedWorkspaceStillEnforcesSourceBudget(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        Files.writeString(fixture.projectRoot().resolve("second.txt"), "second");
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        LocalIsolatedIndexWorker worker = new LocalIsolatedIndexWorker(
                "worker-budget",
                temp.resolve("home"),
                delegate(temp, new AtomicReference<>()),
                store,
                qualifiedBackend(WorkerNetworkPolicy.ALLOW),
                1,
                1024 * 1024,
                Clock.systemUTC());
        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", fixture.materialization(),
                WorkerNetworkPolicy.ALLOW, worker, store);

        Exception failure = assertThrows(Exception.class, () -> executor.execute(fixture.execution()));

        assertTrue(failure.getMessage().contains("source budget"));
    }

    private static WorkerSandboxBackend qualifiedBackend(WorkerNetworkPolicy expectedPolicy) {
        return new WorkerSandboxBackend() {
            @Override public String id() { return "fixture-os-sandbox"; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public NetworkGuarantee networkGuarantee() { return NetworkGuarantee.OS_ENFORCED; }
            @Override
            public WorkerSandboxQualification qualification() {
                return new WorkerSandboxQualification(
                        id(), isolation(), networkGuarantee(),
                        WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                        WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                        Map.of(WorkerSandboxQualification.currentPlatform(),
                                WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                        List.of("TEST_FIXTURE_OS_QUALIFIED"));
            }
            @Override
            public IndexingArtifact execute(
                    IndexerExecutor selected,
                    IndexingExecutionRequest request,
                    WorkerNetworkPolicy networkPolicy
            ) throws Exception {
                assertEquals(expectedPolicy, networkPolicy);
                return selected.execute(request);
            }
        };
    }

    private static LocalIsolatedIndexWorker worker(
            String workerId,
            Path home,
            IndexerExecutor delegate,
            DistributedArtifactBundleStore store,
            WorkerSandboxBackend backend
    ) {
        return new LocalIsolatedIndexWorker(
                workerId, home, delegate, store, backend,
                100_000, 2L * 1024L * 1024L * 1024L, Clock.systemUTC());
    }

    private static LocalIsolatedIndexWorker nativeWorker(
            String workerId,
            Path home,
            IndexerExecutor delegate,
            DistributedArtifactBundleStore store
    ) {
        return worker(workerId, home, delegate, store, WorkerSandboxBackend.nativeEphemeralWorkspace());
    }

    private static IndexerExecutor delegate(Path temp, AtomicReference<Path> delegateRoot) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return "fixture-provider"; }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                delegateRoot.set(request.projectRoot());
                assertTrue(Files.isRegularFile(request.projectRoot().resolve("pom.xml")));
                assertFalse(Files.exists(request.projectRoot().resolve(".git")));
                Path output = Files.writeString(
                        temp.resolve("provider-" + UUID.randomUUID() + ".scip"),
                        "valid-scip-fixture");
                return new IndexingArtifact(Language.JAVA, indexerId(), output);
            }
        };
    }

    private static Fixture fixture(Path temp) throws Exception {
        Path repositoryRoot = Files.createDirectories(temp.resolve("repository"));
        Path projectRoot = Files.createDirectories(repositoryRoot.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Files.createDirectories(projectRoot.resolve(".git"));
        RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                "https://github.com/acme/demo",
                "main",
                "a".repeat(40),
                "project",
                null);
        RemoteMaterialization materialization = new RemoteMaterialization(
                request,
                repositoryRoot,
                projectRoot,
                "c".repeat(64),
                false,
                Instant.parse("2026-07-29T00:00:00Z"));
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture-provider",
                "1.2.3",
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        IndexingExecutionRequest execution = new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                projectRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
        return new Fixture(request, repositoryRoot, projectRoot, materialization, execution);
    }

    private record Fixture(
            RemoteRepositoryRequest request,
            Path repositoryRoot,
            Path projectRoot,
            RemoteMaterialization materialization,
            IndexingExecutionRequest execution
    ) {
    }
}
