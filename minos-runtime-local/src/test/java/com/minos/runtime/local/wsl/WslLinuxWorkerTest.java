package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.sandbox.SandboxTestSupport;
import com.minos.runtime.local.sandbox.WorkerSandboxBackend;
import com.minos.runtime.local.sandbox.WorkerSandboxBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The internal worker run in the distribution (indexation-java-via-wsl › Le provider tourne dans la distribution
 * sous le bac à sable Linux, réseau coupé ; › MINOS ne peuple pas le dépôt Maven désigné).
 */
class WslLinuxWorkerTest {

    private static final String VERSION = "0.13.2";

    @TempDir Path temp;
    private Path home;
    private Path exchange;
    private Path project;

    @BeforeEach
    void layout() throws IOException {
        home = Files.createDirectories(temp.resolve("distribution-home"));
        exchange = Files.createDirectories(temp.resolve("exchange"));
        project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
    }

    @Test
    void withoutADelegatedCgroupTheProviderIsNotLaunched() throws Exception {
        FakeRuntimes runtimes = new FakeRuntimes(ready(), failingExecutor());
        WslLinuxWorker worker = new WslLinuxWorker(home, unused -> WorkerSandboxBackend.nativeEphemeralWorkspace());
        StringBuilder output = new StringBuilder();

        int code = worker.run(request("/mnt/c/project", "scip-java"), runtimes, List.of(descriptor("scip-java")), output);

        assertEquals(WslLinuxWorker.FAILED, code);
        assertTrue(output.toString().startsWith(WslIndexWorker.STATUS_PREFIX + "REFUSED cgroup-delegation"), output.toString());
        assertEquals(0, runtimes.executions.get(), "no provider process is started");
        assertFalse(Files.exists(exchange.resolve(WslWorkerRequest.BUNDLE_NAME)));
    }

    @Test
    void withoutADesignatedRepositoryTheWorkerRefusesByTheSettingAndRunsNoMaven() throws Exception {
        ProviderRuntimeStatus blocked = new ProviderRuntimeStatus("scip-java", VERSION,
                ProviderRuntimeStatus.State.BLOCKED, Optional.empty(), List.of(
                        "set MINOS_SCIP_JAVA_MAVEN_REPOSITORY to a designated Maven repository",
                        "JAVA_HOME does not contain javac: /usr/lib/jvm/broken"), true);
        FakeRuntimes runtimes = new FakeRuntimes(blocked, failingExecutor());
        StringBuilder output = new StringBuilder();

        int code = qualifiedWorker().run(request("/mnt/c/project", "scip-java"), runtimes,
                List.of(descriptor("scip-java")), output);

        assertEquals(WslLinuxWorker.FAILED, code);
        assertTrue(output.toString().contains("MINOS_SCIP_JAVA_MAVEN_REPOSITORY"), output.toString());
        assertFalse(output.toString().contains("/usr/lib"), "a diagnostic naming a path is withheld: " + output);
        assertEquals(0, runtimes.executions.get(), "Maven is never run by a refused worker");
    }

    @Test
    void anotherProviderAnInvalidRequestOrAnotherVersionIsRefused() throws Exception {
        FakeRuntimes runtimes = new FakeRuntimes(ready(), failingExecutor());
        StringBuilder output = new StringBuilder();
        assertEquals(WslLinuxWorker.FAILED, qualifiedWorker().run(request("/mnt/c/project", "scip-python"), runtimes,
                List.of(descriptor("scip-python")), output));
        assertTrue(output.toString().contains("runs scip-java only"), output.toString());

        Path invalid = Files.writeString(exchange.resolve("invalid.properties"), "protocol=minos-wsl-worker-v0\n");
        StringBuilder invalidOutput = new StringBuilder();
        assertEquals(WslLinuxWorker.INVALID_REQUEST, qualifiedWorker().run(invalid, runtimes, List.of(), invalidOutput));
        assertTrue(invalidOutput.toString().contains("REFUSED invalid worker request"), invalidOutput.toString());

        StringBuilder versionOutput = new StringBuilder();
        assertEquals(WslLinuxWorker.FAILED, qualifiedWorker().run(request("/mnt/c/project", "scip-java"), runtimes,
                List.of(), versionOutput));
        assertTrue(versionOutput.toString().contains("is not the provider version of this jar"), versionOutput.toString());
        assertEquals(0, runtimes.executions.get());
    }

    @Test
    void aProjectThatTheDistributionCannotReachIsRefused() throws Exception {
        FakeRuntimes runtimes = new FakeRuntimes(ready(), failingExecutor());
        StringBuilder output = new StringBuilder();

        int code = qualifiedWorker().run(request(project.resolve("missing").toAbsolutePath().toString()
                .replace('\\', '/').replaceFirst("^[A-Za-z]:", ""), "scip-java"), runtimes,
                List.of(descriptor("scip-java")), output);

        assertEquals(WslLinuxWorker.FAILED, code);
        assertTrue(output.toString().contains("not reachable"), output.toString());
    }

    /**
     * Through the real managed local provider sandbox of this host: the bundle is v3, produced under an enforced
     * DENY policy, and the distribution keeps no run directory.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theProviderRunsUnderTheManagedSandboxWithDenyAndTheBundleIsV3() throws Exception {
        WorkerSandboxBackend backend = WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(home);
        SandboxTestSupport.decide(SandboxTestSupport.required(),
                backend.supportsManagedLocalProvider() && backend.enforcesNetworkDeny(),
                () -> "a qualified managed local provider sandbox with network denial is required: " + backend.id());
        ProcessIndexerExecutor process = new ProcessIndexerExecutor("scip-java", home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of("/bin/sh", "-c", "printf 'scip-from-sandbox' > \"$1\"", "provider",
                                runDirectory.resolve("index.scip").toString()),
                        request.projectRoot(), Map.of(), runDirectory.resolve("index.scip"), Duration.ofSeconds(60),
                        List.of(), List.of()));
        IndexerExecutor managed = new StrongProcessOwnershipIndexerExecutor(process, home, WorkerNetworkPolicy.DENY);
        FakeRuntimes runtimes = new FakeRuntimes(ready(), managed);
        StringBuilder output = new StringBuilder();
        Path requestFile = request(project.toString(), "scip-java");

        int code = WslLinuxWorker.production(home).run(requestFile, runtimes, List.of(descriptor("scip-java")), output);

        assertEquals(WslLinuxWorker.SUCCESS, code, output.toString());
        Path bundle = requestFile.resolveSibling(WslWorkerRequest.BUNDLE_NAME);
        DistributedArtifactManifest manifest = DistributedArtifactBundleStore.readManifest(bundle);
        assertEquals(DistributedArtifactManifest.FORMAT_V3, manifest.format());
        assertEquals(WorkerNetworkPolicy.DENY, manifest.networkPolicy());
        assertTrue(manifest.networkDenyEnforced());
        var verified = new DistributedArtifactBundleStore(temp.resolve("windows-home")).accept(bundle);
        assertEquals("scip-from-sandbox", Files.readString(verified.artifact(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(home.resolve("runs").resolve(manifest.runId().toString())),
                "nothing is retained in the distribution");
    }

    private WslLinuxWorker qualifiedWorker() {
        WorkerSandboxBackend qualified = new WorkerSandboxBackend() {
            @Override public String id() { return "qualified-fixture"; }
            @Override public com.minos.remote.DistributedIndexing.WorkerIsolation isolation() {
                return com.minos.remote.DistributedIndexing.WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
            }
            @Override public WorkerSandboxBackend.NetworkGuarantee networkGuarantee() {
                return WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED;
            }
            @Override public boolean supportsManagedLocalProvider() { return true; }
            @Override public boolean enforcesNetworkDeny() { return true; }
            @Override public IndexingArtifact execute(IndexerExecutor executor,
                                                      com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request,
                                                      WorkerNetworkPolicy policy) {
                throw new UnsupportedOperationException("the fixture never runs a provider");
            }
        };
        return new WslLinuxWorker(home, unused -> qualified);
    }

    private Path request(String projectRoot, String provider) throws IOException {
        WslWorkerRequest request = new WslWorkerRequest(UUID.randomUUID(), UUID.randomUUID(), projectRoot, "",
                Language.JAVA, provider, VERSION, "f".repeat(64), WslIndexWorker.WORKER_ID_PREFIX + "Ubuntu");
        Path file = exchange.resolve(UUID.randomUUID() + "-" + WslWorkerRequest.FILE_NAME);
        Files.write(file, request.encode());
        return file;
    }

    private static ProviderRuntimeStatus ready() {
        return new ProviderRuntimeStatus("scip-java", VERSION, ProviderRuntimeStatus.State.READY, Optional.empty(),
                List.of(), true);
    }

    private static IndexerExecutor failingExecutor() {
        return new IndexerExecutor() {
            @Override public String indexerId() { return "scip-java"; }
            @Override public IndexingArtifact execute(
                    com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request) {
                throw new AssertionError("the provider must not start");
            }
        };
    }

    private static IndexerDescriptor descriptor(String id) {
        return new IndexerDescriptor(id, VERSION, id, Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 1, List.of());
    }

    /** Provider runtimes of a fake distribution: one status, one executor, counted executions. */
    private static final class FakeRuntimes implements ProviderRuntimeManager {
        private final ProviderRuntimeStatus status;
        private final IndexerExecutor executor;
        private final AtomicInteger executions = new AtomicInteger();

        private FakeRuntimes(ProviderRuntimeStatus status, IndexerExecutor executor) {
            this.status = status;
            this.executor = executor;
        }

        @Override public List<ProviderRuntimeStatus> list() { return List.of(status); }
        @Override public ProviderRuntimeStatus inspect(String providerId) { return status; }
        @Override public ProviderRuntimeStatus install(String providerId) { return status; }

        @Override
        public IndexerExecutor executor(String providerId) {
            executions.incrementAndGet();
            return executor;
        }
    }
}
