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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial proof that the Windows Job Object is the real boundary: the provider joins it while
 * still suspended, its limits are read back from the kernel, and no descendant outlives the job.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJobObjectContainmentTest {

    @Test
    void neitherChildNorGrandchildSurvivesTheJobObject() throws Exception {
        Path home = Files.createTempDirectory("minos-windows-job-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "a qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        Path project = Files.createTempDirectory("minos-windows-job-project-");
        Path descendants = project.resolve("descendants.txt");

        Path providerScript = project.resolve("provider-child.ps1");
        Files.writeString(providerScript, """
                param([string] $Artifact, [string] $Descendants, [string] $PowerShell)
                $ErrorActionPreference = 'Stop'
                $report = New-Object System.Collections.Generic.List[string]
                function Start-Detached([string] $Command) {
                  $info = New-Object System.Diagnostics.ProcessStartInfo
                  $info.FileName = $PowerShell
                  $info.Arguments = '-NoLogo -NoProfile -NonInteractive -Command "' + $Command + '"'
                  $info.UseShellExecute = $false
                  $info.CreateNoWindow = $true
                  return [System.Diagnostics.Process]::Start($info)
                }
                foreach ($index in 1..2) {
                  try {
                    $spawned = Start-Detached 'Start-Sleep -Seconds 300'
                    $report.Add([string]$spawned.Id)
                  } catch {
                    $report.Add('SPAWN-FAILED ' + $_.Exception.GetType().FullName + ': ' + $_.Exception.Message)
                  }
                }
                Start-Sleep -Milliseconds 500
                [System.IO.File]::WriteAllText($Descendants, ($report -join "`n"))
                [System.IO.File]::WriteAllText($Artifact, 'contained-windows-artifact')
                exit 0
""", StandardCharsets.US_ASCII);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fixture-provider",
                home,
                (ignored, runDirectory) -> {
                    Path generated = runDirectory.resolve("provider-generated.scip");
                    return new IndexerProcessPlan(
                            List.of(
                                    powershell.toString(),
                                    "-NoLogo",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-ExecutionPolicy",
                                    "Bypass",
                                    "-File",
                                    providerScript.toString(),
                                    generated.toString(),
                                    descendants.toString(),
                                    powershell.toString()),
                            project,
                            Map.of(),
                            generated,
                            Duration.ofSeconds(60));
                });

        IndexingArtifact artifact = backend.execute(executor, executionRequest(project), WorkerNetworkPolicy.ALLOW);

        assertEquals("contained-windows-artifact",
                Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(descendants), "the provider must report the descendants it spawned");
        List<String> reported = Files.readAllLines(descendants, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        assertEquals(2, reported.size(), "the provider must report both descendants: " + reported);
        for (String line : reported) {
            if (!line.chars().allMatch(Character::isDigit)) {
                fail("the AppContainer provider could not spawn a descendant: " + line);
            }
            long pid = Long.parseLong(line);
            assertFalse(awaitAlive(pid), "descendant " + pid + " outlived the MINOS job object");
        }
    }

    @Test
    void theSandboxPlanCarriesEveryAggregateJobLimit() throws Exception {
        Path home = Files.createTempDirectory("minos-windows-job-plan-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "a qualified Windows AppContainer backend is required");
        Path working = Files.createTempDirectory("minos-windows-job-plan-working-");
        Path run = Files.createTempDirectory("minos-windows-job-plan-run-");
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of(powershell.toString(), "-NoLogo"),
                working,
                Map.of(),
                run.resolve("index.scip"),
                Duration.ofSeconds(30));

        discovered.orElseThrow().sandboxPlan(plan, run, WorkerNetworkPolicy.DENY);

        String planText = Files.readString(run.resolve("windows-appcontainer-plan.txt"), StandardCharsets.UTF_8);
        assertTrue(planText.contains("jobMemoryBytes="
                + WindowsAppContainerWorkerSandboxBackend.MAX_JOB_MEMORY_BYTES));
        assertTrue(planText.contains("activeProcesses="
                + WindowsAppContainerWorkerSandboxBackend.MAX_ACTIVE_PROCESSES));
        assertTrue(planText.contains("cpuRate=" + WindowsAppContainerWorkerSandboxBackend.CPU_HARD_CAP));
        assertTrue(planText.contains("jobCpuSeconds="
                + WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(30))));

        String launcher = Files.readString(
                home.resolve("sandbox/windows-appcontainer-sandbox-v4.ps1"), StandardCharsets.UTF_8);
        assertTrue(launcher.contains("IsProcessInJob"), "job membership must be verified before resume");
        assertTrue(launcher.contains("TerminateJobObject"), "the job must be terminated on every exit path");
        assertTrue(launcher.contains("JOB_OBJECT_LIMIT_BREAKAWAY_OK"), "breakaway must be explicitly refused");
        assertTrue(launcher.contains("QueryInformationJobObject"), "applied limits must be read back");
    }

    @Test
    void theQualifiedBackendDeclaresTheAggregateContainmentItReallyEnforces() throws Exception {
        Path home = Files.createTempDirectory("minos-windows-job-claim-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "a qualified Windows AppContainer backend is required");
        WorkerSandboxQualification qualification = discovered.orElseThrow().qualification();

        assertTrue(qualification.containment().aggregateJobBoundaryEnforced());
        assertTrue(qualification.sandboxClaimPermitted());
        assertTrue(discovered.orElseThrow().supportsUntrustedCode());
        assertTrue(qualification.limitations().contains("WINDOWS_JOB_BREAKAWAY_PROHIBITED"));
        assertTrue(qualification.limitations().contains("WINDOWS_JOB_TERMINATED_ON_EVERY_EXIT_PATH"));
    }

    private static boolean awaitAlive(long pid) throws InterruptedException {
        for (int poll = 0; poll < 50; poll++) {
            if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) return false;
            Thread.sleep(200L);
        }
        return true;
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
