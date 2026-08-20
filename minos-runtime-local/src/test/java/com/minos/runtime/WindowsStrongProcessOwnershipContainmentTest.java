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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <p>When a wait does expire, the failure quotes the provider's own stdout, stderr and process
 * metadata: a provider that never started has to say why in the report, instead of leaving the next
 * reader to guess between "the script failed" and "the runner was slow".</p>
 *
 * <p>Neither wait sleeps at all. The publication is awaited on the filesystem's own creation event
 * ({@link WatchService}) and the descendant's death on {@link ProcessHandle#onExit()}, the JDK's
 * completion signal for a process this JVM did not start. Both carry a deadline purely so a stalled
 * runner produces a precise failure instead of hanging. No assertion was weakened: the test still
 * proves the kernel boundary kills a process Java never tracked.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsStrongProcessOwnershipContainmentTest {

    /**
     * How long the provider is allowed to run before the ownership boundary is torn down.
     *
     * <p>Deliberately far above the cost of what the provider script does. A hosted Windows runner
     * needed more than twenty seconds merely to start PowerShell and spawn one child, which is why
     * an earlier attempt to shorten this budget to keep the test quick failed in CI while passing
     * locally in under a second. Wall-clock is not the property under test; the ordering "child
     * launched, then budget expires" is, and it is worth a slow test to hold it on any runner.</p>
     */
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(90);

    /** Shorter than the provider budget, so a launch that never happens is reported, not masked. */
    private static final Duration OBSERVATION_DEADLINE = Duration.ofSeconds(75);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);

    private static final Set<String> DIAGNOSTIC_FILES =
            Set.of("provider.stdout.log", "provider.stderr.log", "process.txt");
    private static final int MAX_DIAGNOSTIC_CHARS = 2000;

    /**
     * Starts a long-lived detached descendant and publishes its PID atomically.
     *
     * <p>{@code $PidFile.tmp} then {@code File.Move} is the whole point: the observing test sees the
     * final name only once the complete PID is already in it.</p>
     *
     * <p>The child is a second PowerShell, the shape every previously green run of this test used.
     * Substituting a cheaper process looked like a free improvement and was not measurable as one on
     * the runner, so the known-good shape stays and only the synchronization changed.</p>
     */
    private static final String SPAWN_AND_PUBLISH = """
            $info = [System.Diagnostics.ProcessStartInfo]::new()
            $info.FileName = $PowerShell
            $info.Arguments = '-NoLogo -NoProfile -NonInteractive -Command "[System.Threading.Thread]::Sleep(300000)"'
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
                param([string] $Artifact, [string] $PidFile, [string] $PowerShell)
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
                            providerCommand(powershell, provider, generated.toString(), pidFile.toString(), powershell.toString()),
                            project,
                            Map.of(),
                            generated,
                            PROVIDER_TIMEOUT);
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(delegate, home);
        assumeTrue(executor.capability().strong(), () -> String.join("; ", executor.capability().diagnostics()));

        var artifact = executor.execute(request(project));
        long detachedPid = awaitPublishedPid(pidFile, home);

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
                param([string] $PidFile, [string] $PowerShell)
                """ + SPAWN_AND_PUBLISH + """
                [System.Threading.Thread]::Sleep(600000)
                """, StandardCharsets.UTF_8);

        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        providerCommand(powershell, provider, pidFile.toString(), powershell.toString()),
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
            long childPid = awaitPublishedPid(pidFile, home);
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
     * <p>The wait blocks on the filesystem's own creation event rather than on a timer, so it ends
     * the moment the publication happens whatever the runner's speed. The watch is registered
     * <em>before</em> the existence check, so a file created in between is still observed: the check
     * covers what happened before registration, the events cover everything after. The deadline only
     * decides how long a stalled runner is tolerated before the test reports a precise failure
     * instead of a {@code NoSuchFileException}.</p>
     */
    private static long awaitPublishedPid(Path pidFile, Path home) throws IOException, InterruptedException {
        assertTrue(awaitCreation(pidFile),
                () -> "provider never published a detached child PID at " + pidFile
                        + providerDiagnostics(home));
        try {
            return Long.parseLong(Files.readString(pidFile).trim());
        } catch (Exception failure) {
            throw new AssertionError("published child PID is unreadable", failure);
        }
    }

    private static boolean awaitCreation(Path file) throws IOException, InterruptedException {
        try (WatchService watcher = file.getFileSystem().newWatchService()) {
            file.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            long expiresAt = System.nanoTime() + OBSERVATION_DEADLINE.toNanos();
            while (!Files.isRegularFile(file)) {
                long remaining = expiresAt - System.nanoTime();
                if (remaining <= 0L) {
                    return Files.isRegularFile(file);
                }
                WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key == null) {
                    return Files.isRegularFile(file);
                }
                key.pollEvents();
                key.reset();
            }
            return true;
        }
    }

    /**
     * Everything the provider itself reported, so an expired wait explains its own cause.
     *
     * <p>The executor writes {@code provider.stdout.log}, {@code provider.stderr.log} and {@code
     * process.txt} into the run directory it owns under MINOS_HOME. The test does not need to know
     * where that is: it walks the home it created and quotes what it finds, bounded so a runaway
     * provider cannot turn a failure message into a log dump.</p>
     */
    private static String providerDiagnostics(Path home) {
        StringBuilder report = new StringBuilder();
        try (var paths = Files.walk(home)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> DIAGNOSTIC_FILES.contains(path.getFileName().toString()))
                    .sorted()
                    .forEach(path -> report.append(System.lineSeparator())
                            .append("--- ").append(path.getFileName()).append(" ---")
                            .append(System.lineSeparator())
                            .append(readBounded(path)));
        } catch (IOException unreadable) {
            report.append(System.lineSeparator())
                    .append("provider diagnostics unreadable: ").append(unreadable);
        }
        return report.isEmpty()
                ? System.lineSeparator() + "provider produced no diagnostics at all"
                : report.toString();
    }

    private static String readBounded(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() <= MAX_DIAGNOSTIC_CHARS
                    ? content
                    : content.substring(0, MAX_DIAGNOSTIC_CHARS) + "...[truncated]";
        } catch (IOException unreadable) {
            return "<unreadable: " + unreadable + ">";
        }
    }

    /**
     * Waits for the descendant to die on {@link ProcessHandle#onExit()} -- the JDK's own completion
     * signal for a process this JVM did not start -- rather than on a poll loop.
     */
    private static boolean awaitDead(long pid) throws InterruptedException {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        try {
            handle.orElseThrow().onExit().get(TERMINATION_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expired) {
            return !handle.orElseThrow().isAlive();
        } catch (ExecutionException failure) {
            throw new AssertionError("waiting for the descendant to exit failed", failure);
        }
    }
}
