package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsStrongProcessOwnershipContainmentTest {

    @Test
    void immediateDetachedChildCannotEscapeBeforeFirstJavaObservation() throws Exception {
        Path home = Files.createTempDirectory("minos-strong-owner-windows-home-");
        Path project = Files.createTempDirectory("minos-strong-owner-windows-project-");
        Path pidFile = project.resolve("detached.pid");
        Path provider = project.resolve("immediate-provider.ps1");
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        Files.writeString(provider, """
                param([string] $Artifact, [string] $PidFile, [string] $PowerShell)
                $info = [System.Diagnostics.ProcessStartInfo]::new()
                $info.FileName = $PowerShell
                $info.Arguments = '-NoLogo -NoProfile -NonInteractive -Command "[System.Threading.Thread]::Sleep(300000)"'
                $info.UseShellExecute = $false
                $info.CreateNoWindow = $true
                $child = [System.Diagnostics.Process]::Start($info)
                [System.IO.File]::WriteAllText($PidFile, [string]$child.Id)
                [System.IO.File]::WriteAllText($Artifact, 'strong-owned-artifact')
                exit 0
                """, StandardCharsets.UTF_8);
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                home,
                (request, runDirectory) -> {
                    Path generated = runDirectory.resolve("generated.scip");
                    return new IndexerProcessPlan(
                            List.of(
                                    powershell.toString(),
                                    "-NoLogo",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-ExecutionPolicy",
                                    "Bypass",
                                    "-File",
                                    provider.toString(),
                                    generated.toString(),
                                    pidFile.toString(),
                                    powershell.toString()),
                            project,
                            Map.of(),
                            generated,
                            Duration.ofSeconds(30));
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(delegate, home);
        assumeTrue(executor.capability().strong(), () -> String.join("; ", executor.capability().diagnostics()));

        var artifact = executor.execute(request(project));
        long detachedPid = Long.parseLong(Files.readString(pidFile).trim());

        assertEquals("windows-job-object", executor.capability().mechanism());
        assertEquals("strong-owned-artifact", Files.readString(artifact.finalArtifact()));
        awaitDead(detachedPid);
        assertFalse(ProcessHandle.of(detachedPid).map(ProcessHandle::isAlive).orElse(false),
                "descendant must be terminated when the ownership Job Object closes");
    }

    @Test
    void timeoutClosesJobHandleAndKillsOwnedChild() throws Exception {
        Path home = Files.createTempDirectory("minos-strong-owner-windows-timeout-home-");
        Path project = Files.createTempDirectory("minos-strong-owner-windows-timeout-project-");
        Path pidFile = project.resolve("timeout-child.pid");
        Path provider = project.resolve("timeout-provider.ps1");
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        Files.writeString(provider, """
                param([string] $PidFile, [string] $PowerShell)
                $info = [System.Diagnostics.ProcessStartInfo]::new()
                $info.FileName = $PowerShell
                $info.Arguments = '-NoLogo -NoProfile -NonInteractive -Command "[System.Threading.Thread]::Sleep(300000)"'
                $info.UseShellExecute = $false
                $info.CreateNoWindow = $true
                $child = [System.Diagnostics.Process]::Start($info)
                [System.IO.File]::WriteAllText($PidFile, [string]$child.Id)
                [System.Threading.Thread]::Sleep(300000)
                """, StandardCharsets.UTF_8);
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(
                                powershell.toString(),
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-ExecutionPolicy",
                                "Bypass",
                                "-File",
                                provider.toString(),
                                pidFile.toString(),
                                powershell.toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("never.scip"),
                        Duration.ofSeconds(2)));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(delegate, home);
        assumeTrue(executor.capability().strong(), () -> String.join("; ", executor.capability().diagnostics()));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> executor.execute(request(project)));

        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        awaitDead(childPid);
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "timeout must close the Job Object and kill every owned child");
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    private static void awaitDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
    }
}
