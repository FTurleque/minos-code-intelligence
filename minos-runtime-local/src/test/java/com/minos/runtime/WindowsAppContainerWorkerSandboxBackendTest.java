package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

        String childScript = """
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
                  [System.IO.File]::WriteAllText($args[0], 'escape')
                  exit 42
                } catch { }
                [System.IO.File]::WriteAllText($args[1], 'qualified-appcontainer-artifact')
                exit 0
                """;
        String encoded = Base64.getEncoder().encodeToString(childScript.getBytes(StandardCharsets.UTF_16LE));

        IndexerProcessPlan original = new IndexerProcessPlan(
                List.of(
                        childPowerShell.toString(),
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-EncodedCommand",
                        encoded,
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
}
