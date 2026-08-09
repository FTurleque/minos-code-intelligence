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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsAppContainerWorkerSandboxBackendTest {

    @Test
    void qualificationOnlyPermitsSandboxClaimOnWindows() throws Exception {
        Path home = Files.createTempDirectory("minos-appcontainer-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        if (WorkerSandboxQualification.currentPlatform() == WorkerSandboxQualification.Platform.WINDOWS) {
            assumeTrue(discovered.isPresent(), "Windows PowerShell 5.1 is required for AppContainer qualification");
            assertTrue(discovered.orElseThrow().enforcesNetworkDeny());
            assertTrue(discovered.orElseThrow().qualification().sandboxClaimPermitted());
        } else {
            assertTrue(discovered.isEmpty());
        }
    }

    @Test
    void realWindowsSandboxUsesAppContainerJobLimitsAndBlocksNetworkAndHostWrite() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;

        Path home = Files.createTempDirectory("minos-appcontainer-home-");
        WindowsAppContainerWorkerSandboxBackend backend = WindowsAppContainerWorkerSandboxBackend.discover(home)
                .orElseThrow(() -> new AssertionError("Windows AppContainer backend is unavailable"));
        Path childPowerShell = CommandLocator.find("powershell")
                .orElseThrow(() -> new AssertionError("PowerShell child executable is unavailable"));
        Path working = Files.createTempDirectory("minos-appcontainer-working-");
        Path run = Files.createTempDirectory("minos-appcontainer-run-");
        Path artifact = run.resolve("index.scip");
        Path hostEscape = Path.of(
                System.getProperty("user.home"),
                "minos-appcontainer-escape-" + UUID.randomUUID() + ".txt").toAbsolutePath().normalize();
        Files.deleteIfExists(hostEscape);

        Path childScript = working.resolve("sandbox-child.ps1");
        Files.writeString(childScript, """
                param([string] $HostEscape, [string] $Artifact)
                $ErrorActionPreference = 'Stop'
                $client = $null
                $iar = $null
                try {
                  $client = [System.Net.Sockets.TcpClient]::new()
                  $iar = $client.BeginConnect('1.1.1.1', 53, $null, $null)
                  if ($iar.AsyncWaitHandle.WaitOne(1500) -and $client.Connected) { exit 41 }
                } catch { }
                finally {
                  if ($iar -and $iar.AsyncWaitHandle) { $iar.AsyncWaitHandle.Dispose() }
                  if ($client) { $client.Dispose() }
                }
                try {
                  [System.IO.File]::WriteAllText($HostEscape, 'escape')
                  exit 42
                } catch { }
                [System.IO.File]::WriteAllText($Artifact, 'qualified-appcontainer-artifact')
                exit 0
                """, StandardCharsets.US_ASCII);

        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(
                        childPowerShell.toString(),
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        childScript.toString(),
                        hostEscape.toString(),
                        artifact.toString()),
                working,
                Map.of(),
                artifact,
                Duration.ofSeconds(30));

        IndexerProcessPlan sandboxed = backend.sandboxPlan(original, run);
        Process process = new ProcessBuilder(sandboxed.command())
                .directory(working.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(completed, () -> "AppContainer qualification process timed out; output=" + output);
        assertEquals(0, process.exitValue(), output);
        assertFalse(Files.exists(hostEscape), "AppContainer child must not write outside granted roots");
        assertEquals("qualified-appcontainer-artifact", Files.readString(artifact, StandardCharsets.UTF_8));
    }

    @Test
    void qualifiedBackendLaunchesRealProcessIndexerExecutor() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path home = Files.createTempDirectory("minos-windows-process-home-");
        WindowsAppContainerWorkerSandboxBackend backend = WindowsAppContainerWorkerSandboxBackend.discover(home)
                .orElseThrow(() -> new AssertionError("qualified Windows sandbox backend is unavailable"));
        Path project = Files.createTempDirectory("minos-windows-process-project-");
        Path childPowerShell = CommandLocator.find("powershell")
                .orElseThrow(() -> new AssertionError("PowerShell child executable is unavailable"));
        Path providerScript = project.resolve("provider-child.ps1");
        Files.writeString(providerScript, """
                param([string] $Artifact)
                [System.IO.File]::WriteAllText($Artifact, 'process-sandbox-artifact')
                exit 0
                """, StandardCharsets.US_ASCII);
        IndexingExecutionRequest request = executionRequest(project);
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
                                    generated.toString()),
                            project,
                            Map.of(),
                            generated,
                            Duration.ofSeconds(20));
                });

        IndexingArtifact artifact = backend.execute(executor, request, WorkerNetworkPolicy.DENY);

        assertEquals("fixture-provider", artifact.indexerId());
        assertTrue(Files.isRegularFile(artifact.finalArtifact()));
        assertEquals("process-sandbox-artifact", Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
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
