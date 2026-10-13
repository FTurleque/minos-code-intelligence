package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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

@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapWorkerSandboxBackendTest {

    private static final int STDERR_TAIL_BYTES = 4096;

    @Test
    void denyPlanUsesNetworkNamespaceReadOnlyRootAndResourceLimits() throws Exception {
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        SandboxTestSupport.requireBackend(discovered, "bubblewrap/prlimit runtime is required for Linux sandbox isolation");

        Path working = Files.createTempDirectory("minos-bwrap-working-");
        Path run = Files.createTempDirectory("minos-bwrap-run-");
        Path artifact = run.resolve("index.scip");
        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of("/bin/sh", "-c", "printf artifact > \"$1\"", "sh", artifact.toString()),
                working,
                Map.of(),
                artifact,
                Duration.ofSeconds(30));

        IndexerProcessPlan sandboxed = discovered.orElseThrow().sandboxPlan(plan, run, WorkerNetworkPolicy.DENY);
        List<String> command = sandboxed.command();

        assertTrue(command.contains("--unshare-all"));
        assertFalse(command.contains("--share-net"), "DENY must retain bubblewrap's isolated network namespace");
        assertTrue(command.contains("--cap-drop"));
        assertTrue(command.contains("ALL"));
        assertTrue(command.contains("--ro-bind"));
        assertTrue(command.contains("--as=" + LinuxBubblewrapWorkerSandboxBackend.MAX_ADDRESS_SPACE_BYTES
                + ":" + LinuxBubblewrapWorkerSandboxBackend.MAX_ADDRESS_SPACE_BYTES));
        assertTrue(command.contains("--nproc=" + LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES
                + ":" + LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES));
        assertTrue(command.contains("--nofile=" + LinuxBubblewrapWorkerSandboxBackend.MAX_OPEN_FILES
                + ":" + LinuxBubblewrapWorkerSandboxBackend.MAX_OPEN_FILES));
        assertTrue(discovered.orElseThrow().enforcesNetworkDeny());
        assertFalse(discovered.orElseThrow().qualification().sandboxClaimPermitted(),
                "sampled filesystem limits must not qualify execution of untrusted code");
    }

    @Test
    void realLinuxSandboxBlocksHostWriteAndNetworkAndAppliesRlimits() throws Exception {
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        SandboxTestSupport.requireBackend(discovered, "bubblewrap/prlimit runtime is required for Linux sandbox isolation");
        assumeTrue(CommandLocator.find("python3").isPresent(), "python3 is required for the negative network/resource test");

        Path working = Files.createTempDirectory("minos-bwrap-live-working-");
        Path run = Files.createTempDirectory("minos-bwrap-live-run-");
        Path artifact = run.resolve("index.scip");
        Path hostEscape = Path.of("/var/tmp/minos-bwrap-escape-" + UUID.randomUUID());
        Files.deleteIfExists(hostEscape);

        String python = """
                import resource, socket, sys
                expected_as = int(sys.argv[1])
                expected_nproc = int(sys.argv[2])
                expected_nofile = int(sys.argv[3])
                assert resource.getrlimit(resource.RLIMIT_AS)[0] == expected_as
                assert resource.getrlimit(resource.RLIMIT_NPROC)[0] == expected_nproc
                assert resource.getrlimit(resource.RLIMIT_NOFILE)[0] == expected_nofile
                try:
                    socket.create_connection(('1.1.1.1', 53), timeout=1.0)
                except OSError:
                    pass
                else:
                    raise SystemExit(41)
                """;
        String shell = """
                python3 -c "$1" "$2" "$3" "$4" || exit $?
                if touch "$5" 2>/dev/null; then exit 42; fi
                printf 'qualified-sandbox-artifact' > "$6"
                """;

        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of(
                        "/bin/sh", "-c", shell, "sh",
                        python,
                        Long.toString(LinuxBubblewrapWorkerSandboxBackend.MAX_ADDRESS_SPACE_BYTES),
                        Integer.toString(LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES),
                        Integer.toString(LinuxBubblewrapWorkerSandboxBackend.MAX_OPEN_FILES),
                        hostEscape.toString(),
                        artifact.toString()),
                working,
                Map.of(),
                artifact,
                Duration.ofSeconds(30));

        IndexerProcessPlan sandboxed = discovered.orElseThrow().sandboxPlan(plan, run, WorkerNetworkPolicy.DENY);
        Process process = new ProcessBuilder(sandboxed.command())
                .directory(working.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "sandbox qualification process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertFalse(Files.exists(hostEscape), "sandbox must not write outside its writable roots");
        assertEquals("qualified-sandbox-artifact", Files.readString(artifact, StandardCharsets.UTF_8));
    }

    @Test
    void qualifiedBackendLaunchesRealProcessIndexerExecutor() throws Exception {
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        SandboxTestSupport.requireBackend(discovered, "Linux sandbox backend is required for process-path isolation test");
        LinuxBubblewrapWorkerSandboxBackend backend = discovered.orElseThrow();
        Path home = Files.createTempDirectory("minos-linux-process-home-");
        Path project = Files.createTempDirectory("minos-linux-process-project-");
        IndexingExecutionRequest request = executionRequest(project);
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fixture-provider",
                home,
                (ignored, runDirectory) -> {
                    Path generated = runDirectory.resolve("provider-generated.scip");
                    return new IndexerProcessPlan(
                            List.of("/bin/sh", "-c", "printf 'process-sandbox-artifact' > \"$1\"", "sh", generated.toString()),
                            project,
                            Map.of(),
                            generated,
                            Duration.ofSeconds(20));
                });

        IndexingArtifact artifact;
        try {
            artifact = backend.execute(executor, request, WorkerNetworkPolicy.DENY);
        } catch (IllegalStateException failure) {
            // The executor only names the stderr file, which is gone with the CI host. Carry what the
            // sandbox said into the failure itself; the launch still fails.
            throw new AssertionError(String.valueOf(failure.getMessage()).replace(home.toString(), "<home>")
                    + System.lineSeparator() + launchFailureDiagnostic(home, project, request), failure);
        }

        assertEquals("fixture-provider", artifact.indexerId());
        assertTrue(Files.isRegularFile(artifact.finalArtifact()));
        assertEquals("process-sandbox-artifact", Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
    }

    @Test
    void allowPlanSharesHostNetworkAndDropsCapabilities() throws Exception {
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        SandboxTestSupport.requireBackend(discovered, "bubblewrap/prlimit runtime is required");
        Path working = Files.createTempDirectory("minos-bwrap-allow-working-");
        Path run = Files.createTempDirectory("minos-bwrap-allow-run-");
        Path artifact = run.resolve("index.scip");
        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of("/bin/true"), working, Map.of(), artifact, Duration.ofSeconds(10));
        List<String> command = discovered.orElseThrow().sandboxPlan(plan, run, WorkerNetworkPolicy.ALLOW).command();
        assertTrue(command.contains("--share-net"));
        assertTrue(command.contains("--cap-drop"));
        assertTrue(command.contains("ALL"));
    }

    /**
     * What a failed sandboxed launch left behind: the bounded tail of the provider stderr and the
     * number of tasks of the host account, which is what {@code prlimit --nproc} is compared with
     * when bubblewrap creates its user namespace.
     */
    private static String launchFailureDiagnostic(Path home, Path project, IndexingExecutionRequest request) {
        Path stderr = home.resolve("runs").resolve(request.runId().toString())
                .resolve("fixture-provider").resolve("provider.stderr.log");
        String tail;
        try {
            byte[] bytes = Files.readAllBytes(stderr);
            int from = Math.max(0, bytes.length - STDERR_TAIL_BYTES);
            tail = new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8).strip();
            if (tail.isEmpty()) tail = "<empty>";
        } catch (IOException unreadable) {
            tail = "<unreadable: " + unreadable.getClass().getSimpleName() + ">";
        }
        return "provider stderr: " + tail.replace(home.toString(), "<home>").replace(project.toString(), "<project>")
                + System.lineSeparator() + "host account tasks after the failure: " + hostAccountTasks()
                + " (sandbox prlimit --nproc=" + LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES + ")";
    }

    /** Threads owned by the account running this JVM, as RLIMIT_NPROC counts them; -1 when unreadable. */
    private static long hostAccountTasks() {
        try {
            String account = statusField(Path.of("/proc/self/status"), "Uid:");
            long tasks = 0L;
            try (DirectoryStream<Path> processes = Files.newDirectoryStream(Path.of("/proc"), "[0-9]*")) {
                for (Path process : processes) {
                    try {
                        Path status = process.resolve("status");
                        if (!account.equals(statusField(status, "Uid:"))) continue;
                        tasks += Long.parseLong(statusField(status, "Threads:"));
                    } catch (IOException | RuntimeException _) {
                        // The process exited while it was being read.
                    }
                }
            }
            return tasks;
        } catch (IOException | RuntimeException _) {
            return -1L;
        }
    }

    /** First value of a {@code /proc/<pid>/status} field (the real UID for {@code Uid:}). */
    private static String statusField(Path status, String field) throws IOException {
        for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
            if (line.startsWith(field)) return line.substring(field.length()).strip().split("\\s+")[0];
        }
        throw new IOException("missing field");
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
