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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Windows Job Object ownership: a descendant the provider detaches must still die with the job.
 *
 * <p>Both scenarios hinge on observing a process the test never started itself, and the previous
 * shape of that observation is what made this class flaky under CI load (risk register DT-10). Three
 * things are now deterministic rather than timed:</p>
 * <ul>
 *   <li>the child PID is <b>published atomically</b> -- written to a scratch file and moved into
 *       place -- so a reader can never observe a half-written number;</li>
 *   <li>the PID is read only after that publication is <b>observed</b>, never "once the executor
 *       returned and the file has probably been written by now";</li>
 *   <li>in the timeout scenario the execution runs on its own thread, so the test can prove the
 *       child was launched <em>and alive</em> before the provider timeout fires, instead of
 *       discovering afterwards that the two had raced.</li>
 * </ul>
 *
 * <p>The detached child is {@code ping}, not a second PowerShell: starting it costs milliseconds
 * instead of seconds, so the launch is comfortably inside the provider budget even on a saturated
 * runner. It is an ordinary descendant process, which is exactly what the containment claim is
 * about. Nothing here waits for a fixed delay, and no assertion was weakened: the test still proves
 * the kernel boundary kills a process Java never tracked.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsStrongProcessOwnershipContainmentTest {

    /**
     * Ten to twenty times what launching PowerShell plus {@code ping} costs, so the ordering the
     * timeout scenario depends on -- child launched, then budget expires -- holds even on a badly
     * saturated runner, while the test still finishes in seconds rather than minutes.
     */
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(20);

    /** Shorter than the provider budget, so a missing launch is reported instead of masked. */
    private static final Duration OBSERVATION_DEADLINE = Duration.ofSeconds(15);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);

    /**
     * Starts a long-lived detached descendant and publishes its PID atomically.
     *
     * <p>{@code $PidFile.tmp} then {@code File.Move} is the whole point: the observing test sees the
     * final name only once the complete PID is already in it.</p>
     */
    private static final String SPAWN_AND_PUBLISH = """
            $info = [System.Diagnostics.ProcessStartInfo]::new()
            $info.FileName = (Join-Path $env:SystemRoot 'System32\\PING.EXE')
            $info.Arguments = '-n 300 127.0.0.1'
            $info.UseShellExecute = $false
            $info.CreateNoWindow = $true
            $child = [System.Diagnostics.Process]::Start($info)
            $staging = $PidFile + '.tmp'
            [System.IO.File]::WriteAllText($staging, [string]$child.Id)
            [System.IO.File]::Move($staging, $PidFile)
            """;

    @Test
    void immediateDetachedChildCannotEscapeBeforeFirstJavaObservation() throws Exception {
        Path home = Files.createTempDirectory("minos-strong-owner-windows-home-");
        Path project = Files.createTempDirectory("minos-strong-owner-windows-project-");
        Path pidFile = project.resolve("detached.pid");
        Path provider = project.resolve("immediate-provider.ps1");
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        Files.writeString(provider, """
                param([string] $Artifact, [string] $PidFile)
                """ + SPAWN_AND_PUBLISH + """
                [System.IO.File]::WriteAllText($Artifact, 'strong-owned-artifact')
                exit 0
                """, StandardCharsets.UTF_8);

        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                home,
                (request, runDirectory) -> {
                    Path generated = runDirectory.resolve("generated.scip");
                    return new IndexerProcessPlan(
                            providerCommand(powershell, provider, generated.toString(), pidFile.toString()),
                            project,
                            Map.of(),
                            generated,
                            PROVIDER_TIMEOUT);
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(delegate, home);
        assumeTrue(executor.capability().strong(), () -> String.join("; ", executor.capability().diagnostics()));

        var artifact = executor.execute(request(project));
        long detachedPid = awaitPublishedPid(pidFile);

        assertEquals("windows-job-object", executor.capability().mechanism());
        assertEquals("strong-owned-artifact", Files.readString(artifact.finalArtifact()));
        assertTrue(awaitDead(detachedPid),
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
                param([string] $PidFile)
                """ + SPAWN_AND_PUBLISH + """
                [System.Threading.Thread]::Sleep(600000)
                """, StandardCharsets.UTF_8);

        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        providerCommand(powershell, provider, pidFile.toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("never.scip"),
                        PROVIDER_TIMEOUT));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(delegate, home);
        assumeTrue(executor.capability().strong(), () -> String.join("; ", executor.capability().diagnostics()));

        ExecutorService runner = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "strong-ownership-timeout-execution");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> execution = runner.submit(() ->
                    assertThrows(IllegalStateException.class, () -> executor.execute(request(project))));

            // Reading the PID while the provider is still running is what makes the assertion
            // meaningful: it establishes that the child existed *before* the timeout, rather than
            // hoping the two happened in that order.
            long childPid = awaitPublishedPid(pidFile);
            assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "the detached child must still be running when the provider budget expires");

            execution.get(PROVIDER_TIMEOUT.toSeconds() + TERMINATION_DEADLINE.toSeconds(), TimeUnit.SECONDS);

            assertTrue(awaitDead(childPid),
                    "timeout must close the Job Object and kill every owned child");
        } finally {
            runner.shutdownNow();
        }
    }

    private static List<String> providerCommand(Path powershell, Path script, String... arguments) {
        List<String> command = new java.util.ArrayList<>(List.of(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString()));
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    /**
     * Waits for the atomic publication of the child PID and returns it.
     *
     * <p>The wait is on the publication itself, so it ends as soon as the signal is there whatever
     * the runner's speed; the deadline only decides how long a stalled runner is tolerated before
     * the test reports a precise failure instead of a {@code NoSuchFileException}.
     */
    private static long awaitPublishedPid(Path pidFile) throws InterruptedException {
        assertTrue(await(() -> Files.isRegularFile(pidFile), OBSERVATION_DEADLINE),
                () -> "provider never published a detached child PID at " + pidFile);
        try {
            return Long.parseLong(Files.readString(pidFile).trim());
        } catch (Exception failure) {
            throw new AssertionError("published child PID is unreadable", failure);
        }
    }

    private static boolean awaitDead(long pid) throws InterruptedException {
        return await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                TERMINATION_DEADLINE);
    }

    /** Polls a real condition until it holds or the deadline expires; never sleeps for its own sake. */
    private static boolean await(BooleanSupplier condition, Duration deadline) throws InterruptedException {
        long expiresAt = System.nanoTime() + deadline.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= expiresAt) {
                return condition.getAsBoolean();
            }
            Thread.sleep(25L);
        }
        return true;
    }
}
