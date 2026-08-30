package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.runtime.DistributedArtifactBundleStore;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;

import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRemoteIndexOperationsIntegrationTest {

    @Test
    void remoteRevisionFlowsThroughWorkerTransportStagingAndAtomicLocalSnapshot(@TempDir Path temp)
            throws Exception {
        Path home = temp.resolve("home");
        Path repository = Files.createDirectories(temp.resolve("remote-repository"));
        Path project = Files.createDirectories(repository.resolve("fixture"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path source = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("RemoteType.java"), "package demo; class RemoteType {}\n");

        IndexerDescriptor descriptor = descriptor();
        ProviderRuntimeManager runtime = runtime(temp, descriptor);
        MinosApplication application = MinosApplication.builder(home)
                .indexerDescriptors(List.of(descriptor))
                .providerRuntimeManager(runtime)
                .build();
        RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                "https://github.com/acme/remote-fixture", "main", "a".repeat(40), "fixture", null);
        RemoteMaterialization materialization = new RemoteMaterialization(
                request, repository, project, "b".repeat(64), false,
                Instant.parse("2026-07-29T00:00:00Z"));
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(home);
        LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(
                application,
                ignored -> materialization,
                store,
                (workerId, delegate, artifactStore) -> trustedFixtureWorker(workerId, delegate, artifactStore, temp));

        RemoteIndexOperations.RemoteIndexView result = operations.index(
                request, "remote-fixture", "fixture-provider", "worker-one", WorkerNetworkPolicy.ALLOW);

        assertEquals("SUCCEEDED", result.execution().status());
        assertTrue(result.execution().fingerprintPromoted());
        assertEquals(1, result.artifacts().size());
        assertEquals("fixture-provider", result.artifacts().getFirst().providerId());
        assertEquals("PROCESS_EPHEMERAL_WORKSPACE", result.artifacts().getFirst().isolation());
        assertFalse(result.artifacts().getFirst().networkDenyEnforced());
        assertEquals("", result.artifacts().getFirst().projectRelativeRoot());
        var snapshot = application.snapshotStore().loadActiveKnowledge(
                java.util.UUID.fromString(result.projectId())).orElseThrow();
        assertEquals(result.execution().activeSnapshotId(), snapshot.snapshotId());
        assertTrue(snapshot.symbols().stream().anyMatch(symbol -> "RemoteType".equals(symbol.name())));
        assertEquals("fixture-provider", snapshot.symbols().getFirst().origin().providerId());
        assertEquals("1.2.3", snapshot.symbols().getFirst().origin().providerVersion());
    }

    @Test
    void releasesMaterializationWhenRemoteIndexLeaseAcquisitionFails(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path repository = Files.createDirectories(temp.resolve("remote-repository"));
        Path project = Files.createDirectories(repository.resolve("fixture"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        IndexerDescriptor descriptor = descriptor();
        ProviderRuntimeManager runtime = runtime(temp, descriptor);
        MinosApplication application = MinosApplication.builder(home)
                .indexerDescriptors(List.of(descriptor))
                .providerRuntimeManager(runtime)
                .build();

        RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                "https://github.com/acme/remote-fixture", "main", "a".repeat(40), "fixture", null);
        RemoteMaterialization materialization = new RemoteMaterialization(
                request, repository, project, "c".repeat(64), false,
                Instant.parse("2026-07-29T00:00:00Z"));

        AtomicInteger releaseCount = new AtomicInteger();
        AtomicInteger pinCount = new AtomicInteger();
        RemoteRepositoryMaterializer materializer = new RemoteRepositoryMaterializer() {
            @Override
            public RemoteMaterialization materialize(RemoteRepositoryRequest ignored) {
                return materialization;
            }

            @Override
            public void pin(RemoteMaterialization ignored) {
                pinCount.incrementAndGet();
            }

            @Override
            public void release(RemoteMaterialization ignored) {
                releaseCount.incrementAndGet();
            }
        };

        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(home);
        LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(
                application, materializer, store,
                (workerId, delegate, artifactStore) -> {
                    throw new AssertionError("worker must never be created when the lease is never acquired");
                });

        // Force RemoteIndexLease.acquire(...) to fail deterministically and without waiting on its
        // real timeout: its lock directory cannot be created because a regular file already occupies
        // that exact path.
        Files.writeString(home.resolve("remote-index-leases"), "not a directory");

        Exception failure = assertThrows(Exception.class, () -> operations.index(
                request, "remote-fixture", "fixture-provider", "worker-one", WorkerNetworkPolicy.ALLOW));

        assertTrue(failure instanceof FileAlreadyExistsException,
                "expected the lease directory creation failure to propagate unwrapped: " + failure);
        assertEquals(1, releaseCount.get(),
                "a successful materialize() must be released exactly once even when lease acquisition fails");
        assertEquals(0, pinCount.get(), "pin must never run before the remote index lease is held");
    }

    /** Test-only worker: transport/staging is under test here; OS sandbox qualification is covered separately. */
    private static Worker trustedFixtureWorker(
            String workerId,
            IndexerExecutor delegate,
            DistributedArtifactBundleStore store,
            Path temp
    ) {
        return new Worker() {
            @Override public String workerId() { return workerId; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public boolean enforcesNetworkDeny() { return false; }

            @Override
            public WorkerResponse execute(WorkerRequest request) throws Exception {
                var artifact = delegate.execute(request.execution());
                long bytes = Files.size(artifact.finalArtifact());
                String scope = request.execution().projectRelativeRoot().toString().replace('\\', '/');
                DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                        DistributedArtifactManifest.FORMAT_V2,
                        request.execution().runId(), request.execution().projectId(), scope,
                        request.sourceRepository(), request.sourceCommit(), artifact.language(), artifact.indexerId(),
                        request.providerVersion(), workerId, isolation(), request.networkPolicy(), false,
                        Instant.parse("2026-08-13T00:00:00Z"), Instant.parse("2026-08-13T00:00:01Z"),
                        DistributedArtifactManifest.ARTIFACT_PATH, bytes,
                        DistributedArtifactBundleStore.sha256(artifact.finalArtifact()));
                Path bundle = Files.createTempFile(temp, "remote-e2e-", ".zip");
                store.createBundle(bundle, manifest, artifact.finalArtifact());
                return new WorkerResponse(bundle, manifest);
            }
        };
    }

    private static IndexerDescriptor descriptor() {
        return new IndexerDescriptor(
                "fixture-provider", "1.2.3", "Fixture provider",
                Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of("M25_TEST_FIXTURE"));
    }

    private static ProviderRuntimeManager runtime(Path temp, IndexerDescriptor descriptor) throws Exception {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus(
                descriptor.id(), descriptor.version(), ProviderRuntimeStatus.State.READY,
                Optional.of(temp.resolve("fixture-provider")), List.of(), true);
        Path fixtureArtifact = temp.resolve("fixture-provider-output.scip");
        writeIndex(fixtureArtifact);
        Path windowsCopyScript = temp.resolve("fixture-provider-copy.ps1");
        Files.writeString(windowsCopyScript, """
                param([string] $Source, [string] $Target)
                $ErrorActionPreference = 'Stop'
                [System.IO.File]::Copy($Source, $Target, $true)
                """, java.nio.charset.StandardCharsets.US_ASCII);
        IndexerExecutor executor = new ProcessIndexerExecutor(
                descriptor.id(), temp.resolve("provider-runtime-home"),
                (request, runDirectory) -> {
                    Path artifact = runDirectory.resolve("fixture-" + request.runId() + ".scip");
                    return new IndexerProcessPlan(
                            copyCommand(fixtureArtifact, artifact, windowsCopyScript),
                            request.projectRoot(), Map.of(), artifact, Duration.ofSeconds(20));
                });
        return new ProviderRuntimeManager() {
            @Override public List<ProviderRuntimeStatus> list() { return List.of(ready); }
            @Override public ProviderRuntimeStatus inspect(String providerId) { return ready; }
            @Override public ProviderRuntimeStatus install(String providerId) { return ready; }
            @Override public IndexerExecutor executor(String providerId) { return executor; }
        };
    }

    private static List<String> copyCommand(Path source, Path target, Path windowsScript) {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String systemRoot = System.getenv("SystemRoot");
            Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                    .toAbsolutePath().normalize();
            return List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", windowsScript.toString(), source.toString(), target.toString());
        }
        return List.of("/bin/cp", source.toString(), target.toString());
    }

    private static void writeIndex(Path file) throws Exception {
        String rawSymbol = "scip-java maven fixture 1.0 demo/RemoteType#";
        SymbolInformation symbol = SymbolInformation.newBuilder()
                .setSymbol(rawSymbol).setDisplayName("RemoteType").setKind(SymbolInformation.Kind.Class).build();
        Occurrence definition = Occurrence.newBuilder()
                .setSymbol(rawSymbol).setSymbolRoles(SymbolRole.Definition_VALUE)
                .setSingleLineRange(SingleLineRange.newBuilder().setLine(0).setStartCharacter(20).setEndCharacter(30))
                .build();
        Document document = Document.newBuilder()
                .setLanguage("java").setRelativePath("src/main/java/demo/RemoteType.java")
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(symbol).addOccurrences(definition).build();
        try (OutputStream output = Files.newOutputStream(file)) {
            Index.newBuilder().addDocuments(document).build().writeTo(output);
        }
    }
}
