package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsSandboxSensitivePlanTest {

    @Test
    void sensitiveTransportPlanIsErasedWhileProviderStillRuns() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;

        Path home = Files.createTempDirectory("minos-sensitive-plan-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path childPowerShell = CommandLocator.windowsPowerShell().orElseThrow();
        Path project = Files.createTempDirectory("minos-sensitive-plan-project-");
        Path marker = project.resolve("provider-started.marker");
        Path providerScript = project.resolve("provider-sensitive-plan.ps1");
        String sentinel = "minos-sensitive-" + UUID.randomUUID();
        Files.writeString(providerScript, """
                param([string] $ExpectedSecret, [string] $Marker, [string] $Artifact)
                if ($env:MINOS_TEST_PROVIDER_SECRET -ne $ExpectedSecret) { exit 61 }
                [System.IO.File]::WriteAllText($Marker, 'started')
                Start-Sleep -Milliseconds 1500
                [System.IO.File]::WriteAllText($Artifact, 'sensitive-plan-artifact')
                exit 0
                """, StandardCharsets.US_ASCII);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fixture-provider",
                home,
                (ignored, runDirectory) -> {
                    Path generated = runDirectory.resolve("provider-generated.scip");
                    return new IndexerProcessPlan(
                            List.of(
                                    childPowerShell.toString(),
                                    "-NoLogo",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-ExecutionPolicy",
                                    "Bypass",
                                    "-File",
                                    providerScript.toString(),
                                    sentinel,
                                    marker.toString(),
                                    generated.toString()),
                            project,
                            Map.of("MINOS_TEST_PROVIDER_SECRET", sentinel),
                            generated,
                            Duration.ofSeconds(60));
                });

        ExecutorService asynchronous = Executors.newSingleThreadExecutor();
        try {
            Future<IndexingArtifact> execution = asynchronous.submit(
                    () -> backend.execute(executor, executionRequest(project), WorkerNetworkPolicy.ALLOW));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(marker) && System.nanoTime() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue(Files.exists(marker), "provider must have started inside AppContainer");
            assertFalse(containsSensitivePlan(home.resolve("runs")),
                    "sandbox transport plan must be erased immediately after launcher parsing");

            IndexingArtifact artifact = execution.get(75, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(artifact.finalArtifact()));
            assertFalse(containsSensitivePlan(home.resolve("runs")),
                    "sandbox transport plan must never survive provider execution");
        } finally {
            asynchronous.shutdownNow();
        }
    }

    private static boolean containsSensitivePlan(Path runsRoot) throws Exception {
        if (!Files.isDirectory(runsRoot)) return false;
        try (var paths = Files.walk(runsRoot)) {
            return paths.anyMatch(path -> "windows-appcontainer-plan.txt".equals(String.valueOf(path.getFileName())));
        }
    }

    private static IndexingExecutionRequest executionRequest(Path projectRoot) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture-provider",
                "1.0.0",
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                projectRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
