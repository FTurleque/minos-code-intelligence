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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributedIndexerExecutorScopeTest {

    @Test
    void materializedRegisteredRootAcceptsNestedModuleScopesAndRetainsEveryEvidence(@TempDir Path temp)
            throws Exception {
        Path repositoryRoot = Files.createDirectories(temp.resolve("repository"));
        Path registeredRoot = Files.createDirectories(repositoryRoot.resolve("project"));
        Path moduleA = Files.createDirectories(registeredRoot.resolve("module-a"));
        Path moduleB = Files.createDirectories(registeredRoot.resolve("module-b"));
        Files.writeString(moduleA.resolve("pom.xml"), "<project/>");
        Files.writeString(moduleB.resolve("pom.xml"), "<project/>");

        RemoteRepositoryRequest sourceRequest = RemoteRepositoryRequest.of(
                "https://github.com/acme/demo",
                "main",
                "a".repeat(40),
                "project",
                null);
        RemoteMaterialization source = new RemoteMaterialization(
                sourceRequest,
                repositoryRoot,
                registeredRoot,
                "c".repeat(64),
                false,
                Instant.parse("2026-08-13T00:00:00Z"));

        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(temp.resolve("home"));
        AtomicInteger sequence = new AtomicInteger();
        Worker worker = new Worker() {
            @Override public String workerId() { return "scope-worker"; }
            @Override public WorkerIsolation isolation() {
                return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
            }
            @Override public boolean enforcesNetworkDeny() { return false; }

            @Override
            public WorkerResponse execute(WorkerRequest request) throws Exception {
                int index = sequence.incrementAndGet();
                Path artifact = Files.writeString(temp.resolve("scope-" + index + ".scip"), "scope-" + index);
                String scope = request.execution().projectRelativeRoot().toString().replace('\\', '/');
                DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                        DistributedArtifactManifest.FORMAT_V2,
                        request.execution().runId(),
                        request.execution().projectId(),
                        scope,
                        request.sourceRepository(),
                        request.sourceCommit(),
                        request.execution().selection().language(),
                        request.execution().selection().indexer().id(),
                        request.providerVersion(),
                        workerId(),
                        isolation(),
                        request.networkPolicy(),
                        false,
                        Instant.parse("2026-08-13T00:00:00Z"),
                        Instant.parse("2026-08-13T00:00:01Z"),
                        DistributedArtifactManifest.ARTIFACT_PATH,
                        Files.size(artifact),
                        DistributedArtifactBundleStore.sha256(artifact));
                Path bundle = store.createBundle(temp.resolve("scope-" + index + ".zip"), manifest, artifact);
                return new WorkerResponse(bundle, manifest);
            }
        };

        DistributedIndexerExecutor executor = new DistributedIndexerExecutor(
                "fixture-provider", "1.2.3", source, WorkerNetworkPolicy.ALLOW, worker, store);
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        executor.execute(execution(runId, projectId, registeredRoot, moduleA, "module-a"));
        executor.execute(execution(runId, projectId, registeredRoot, moduleB, "module-b"));

        assertEquals(List.of("module-a", "module-b"), executor.verifiedArtifacts().stream()
                .map(value -> value.manifest().projectRelativeRoot())
                .toList());
        executor.close();
    }

    private static IndexingExecutionRequest execution(
            UUID runId,
            UUID projectId,
            Path registeredRoot,
            Path executionRoot,
            String scope
    ) {
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
        return new IndexingExecutionRequest(
                runId,
                projectId,
                registeredRoot,
                executionRoot,
                Path.of(scope),
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
