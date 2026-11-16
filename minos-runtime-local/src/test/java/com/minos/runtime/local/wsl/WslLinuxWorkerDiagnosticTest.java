package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.diagnostics.WorkerDiagnostic;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.sandbox.WorkerSandboxBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A worker that fails by exception says so before its status, with a reference and neither message nor path. */
class WslLinuxWorkerDiagnosticTest {

    private static final String VERSION = "0.13.2";
    private static final String PREFIX = WslIndexWorker.STATUS_PREFIX;

    @TempDir Path temp;
    private Path home;
    private Path exchange;

    @BeforeEach
    void layout() throws IOException {
        home = Files.createDirectories(temp.resolve("distribution-home"));
        exchange = Files.createDirectories(temp.resolve("exchange"));
        Files.createDirectories(temp.resolve("project"));
    }

    @Test
    void anExceptionOfTheProviderIsPrecededByADiagnosticLineWithTheSameReferenceAsTheStatus() throws Exception {
        StringBuilder output = new StringBuilder();

        int code = worker().run(request(reachableProjectRoot()), runtimes(throwing(new NullPointerException())),
                List.of(descriptor()), output);

        assertEquals(WslLinuxWorker.FAILED, code);
        List<String> lines = output.toString().lines().toList();
        assertEquals(2, lines.size(), output.toString());
        assertTrue(lines.get(0).startsWith(PREFIX + "DIAGNOSTIC ref="), lines.get(0));
        WorkerDiagnostic diagnostic = WorkerDiagnostic.parse(lines.get(0).substring(PREFIX.length())).orElseThrow();
        assertEquals(List.of("java.lang.NullPointerException"), diagnostic.types());
        assertTrue(diagnostic.origin().startsWith("com.minos.runtime.local.wsl.WslLinuxWorkerDiagnosticTest."),
                diagnostic.origin());
        assertEquals(PREFIX + "FAILED scip-java: NullPointerException (ref " + diagnostic.reference() + ")",
                lines.get(1));
        for (String line : lines) {
            assertFalse(line.contains("/mnt/") || line.contains("/home/") || line.contains("C:\\"), line);
        }
    }

    @Test
    void aRefusalCarriesNoDiagnosticLine() throws Exception {
        StringBuilder output = new StringBuilder();

        int code = worker().run(request("/mnt/c/does/not/exist"), runtimes(throwing(new NullPointerException())),
                List.of(descriptor()), output);

        assertEquals(WslLinuxWorker.FAILED, code);
        assertFalse(output.toString().contains("DIAGNOSTIC"), output.toString());
        assertTrue(output.toString().startsWith(PREFIX + "REFUSED"), output.toString());
    }

    /** A directory that exists on every host, named the way the distribution names a path: no drive letter. */
    private static String reachableProjectRoot() {
        return Path.of("").toAbsolutePath().toString().replace('\\', '/').replaceFirst("^[A-Za-z]:", "");
    }

    private WslLinuxWorker worker() {
        WorkerSandboxBackend qualified = new WorkerSandboxBackend() {
            @Override public String id() { return "qualified-fixture"; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public WorkerSandboxBackend.NetworkGuarantee networkGuarantee() {
                return WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED;
            }
            @Override public boolean supportsManagedLocalProvider() { return true; }
            @Override public boolean enforcesNetworkDeny() { return true; }
            @Override public IndexingArtifact execute(IndexerExecutor executor, IndexingExecutionRequest request,
                                                      WorkerNetworkPolicy policy) {
                throw new UnsupportedOperationException("the fixture never runs a provider");
            }
        };
        return new WslLinuxWorker(home, unused -> qualified);
    }

    private Path request(String projectRoot) throws IOException {
        WslWorkerRequest request = new WslWorkerRequest(UUID.randomUUID(), UUID.randomUUID(), projectRoot, "",
                Language.JAVA, "scip-java", VERSION, "f".repeat(64), WslIndexWorker.WORKER_ID_PREFIX + "Ubuntu");
        Path file = exchange.resolve(UUID.randomUUID() + "-" + WslWorkerRequest.FILE_NAME);
        Files.write(file, request.encode());
        return file;
    }

    private static IndexerDescriptor descriptor() {
        return new IndexerDescriptor("scip-java", VERSION, "scip-java", Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS), IndexerQualification.QUALIFIED, 1,
                List.of());
    }

    private static IndexerExecutor throwing(RuntimeException failure) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return "scip-java"; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) { throw failure; }
        };
    }

    private static ProviderRuntimeManager runtimes(IndexerExecutor executor) {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus("scip-java", VERSION,
                ProviderRuntimeStatus.State.READY, Optional.empty(), List.of(), true);
        return new ProviderRuntimeManager() {
            @Override public List<ProviderRuntimeStatus> list() { return List.of(ready); }
            @Override public ProviderRuntimeStatus inspect(String providerId) { return ready; }
            @Override public ProviderRuntimeStatus install(String providerId) { return ready; }
            @Override public IndexerExecutor executor(String providerId) { return executor; }
        };
    }
}
