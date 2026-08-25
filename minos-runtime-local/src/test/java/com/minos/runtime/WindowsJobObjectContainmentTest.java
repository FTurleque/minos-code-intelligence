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
 * Adversarial proof that the Windows Job Object is the real process boundary: the provider joins it
 * while still suspended, its limits are read back from the kernel, and no descendant outlives the
 * job. Filesystem bytes/entries remain a separate hard-quota requirement.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJobObjectContainmentTest {

    @Test
    void neitherChildNorGrandchildSurvivesTheJobObject() throws Exception {
        Path home = Files.createTempDirectory("minos-windows-job-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "a Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        Path project = Files.createTempDirectory("minos-windows-job-project-");
        Path descendants = project.resolve("descendants.txt");

        Path providerScript = project.resolve("provider-child.ps1");
        Files.writeString(providerScript, """
                param([string] $Artifact, [string] $Descendants, [string] $PowerShell)
                $ErrorActionPreference = 'Continue'
                $journal = "begin`r`n"
                [System.IO.File]::WriteAllText($Descendants, $journal)
                foreach ($index in 1, 2) {
                  try {
                    $info = [System.Diagnostics.ProcessStartInfo]::new()
                    $info.FileName = $PowerShell
                    $info.Arguments = '-NoLogo -NoProfile -NonInteractive -Command "[System.Threading.Thread]::Sleep(300000)"'
                    $info.UseShellExecute = $false
                    $info.CreateNoWindow = $true
                    $spawned = [System.Diagnostics.Process]::Start($info)
                    $journal = $journal + 'pid=' + $spawned.Id + "`r`n"
                  } catch {
                    $journal = $journal + 'spawn-failed=' + $_.Exception.Message + "`r`n"
                  }
                  [System.IO.File]::WriteAllText($Descendants, $journal)
                }
                [System.Threading.Thread]::Sleep(500)
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

        IndexingArtifact artifact;
        try {
            artifact = backend.execute(executor, executionRequest(project), WorkerNetworkPolicy.ALLOW);
        } catch (Exception failure) {
            throw new AssertionError(
                    failure.getMessage() + "\nprovider diagnostics:\n" + providerDiagnostics(home), failure);
        }

        assertEquals("contained-windows-artifact",
                Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(descendants), "the provider must report the descendants it spawned");
        List<String> journal = Files.readAllLines(descendants, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        String evidence = "descendant journal=" + journal + "\nprovider diagnostics:\n" + providerDiagnostics(home);
        assertTrue(journal.contains("begin"), () -> "the provider fixture did not run: " + evidence);
        journal.stream()
                .filter(line -> line.startsWith("spawn-failed="))
                .findFirst()
                .ifPresent(line -> fail("the AppContainer provider could not spawn a descendant: " + line));
        List<String> pids = journal.stream().filter(line -> line.startsWith("pid=")).toList();
        assertEquals(2, pids.size(), () -> "the provider must report both descendants: " + evidence);
        for (String line : pids) {
            long pid = Long.parseLong(line.substring("pid=".length()));
            assertFalse(awaitAlive(pid), "descendant " + pid + " outlived the MINOS job object");
        }
    }

    @Test
    void theSandboxPlanCarriesEveryAggregateJobLimit() throws Exception {
        Path home = Files.createTempDirectory("minos-windows-job-plan-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "a Windows AppContainer backend is required");
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
        assumeTrue(discovered.isPresent(), "a Windows AppContainer backend is required");
        WorkerSandboxQualification qualification = discovered.orElseThrow().qualification();

        assertTrue(qualification.containment().aggregateJobBoundaryEnforced());
        assertFalse(qualification.containment().hardFilesystemQuotaEnforced());
        assertFalse(qualification.sandboxClaimPermitted());
        assertFalse(discovered.orElseThrow().supportsUntrustedCode());
        assertEquals(
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                qualification.trustDisposition());
        assertTrue(qualification.limitations().contains("WINDOWS_JOB_BREAKAWAY_PROHIBITED"));
        assertTrue(qualification.limitations().contains("WINDOWS_JOB_TERMINATED_ON_EVERY_EXIT_PATH"));
        assertTrue(qualification.limitations().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_BYTES")));
        assertTrue(qualification.limitations().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_ENTRIES")));
    }

    private static String providerDiagnostics(Path home) {
        Path runs = home.resolve("runs");
        if (!Files.isDirectory(runs)) return "<no MINOS run directory>";
        StringBuilder diagnostics = new StringBuilder();
        try (var entries = Files.walk(runs, 6)) {
            for (Path candidate : entries.filter(Files::isRegularFile).toList()) {
                String name = String.valueOf(candidate.getFileName());
                if (!name.equals("provider.stderr.log") && !name.equals("provider.stdout.log")) continue;
                diagnostics.append(name).append(": ")
                        .append(Files.readString(candidate, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (Exception exception) {
            diagnostics.append("<unreadable: ").append(exception.getMessage()).append('>');
        }
        return diagnostics.isEmpty() ? "<no provider diagnostics>" : diagnostics.toString();
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
