package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinuxBubblewrapWorkerSandboxBackendTest {

    @Test
    void denyPlanUsesNetworkNamespaceReadOnlyRootAndResourceLimits() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) return;
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        assumeTrue(discovered.isPresent(), "bubblewrap/prlimit are required for Linux sandbox qualification");

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
        assertFalse(command.contains("--share-net"));
        assertTrue(command.contains("--ro-bind"));
        assertTrue(command.contains("--cap-drop"));
        assertTrue(command.contains("--as=" + LinuxBubblewrapWorkerSandboxBackend.MAX_ADDRESS_SPACE_BYTES
                + ":" + LinuxBubblewrapWorkerSandboxBackend.MAX_ADDRESS_SPACE_BYTES));
        assertTrue(command.contains("--nproc=" + LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES
                + ":" + LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES));
        assertTrue(command.contains("--nofile=" + LinuxBubblewrapWorkerSandboxBackend.MAX_OPEN_FILES
                + ":" + LinuxBubblewrapWorkerSandboxBackend.MAX_OPEN_FILES));
        assertTrue(discovered.orElseThrow().enforcesNetworkDeny());
        assertTrue(discovered.orElseThrow().qualification().sandboxClaimPermitted());
    }

    @Test
    void realLinuxSandboxBlocksHostWriteAndNetworkAndAppliesRlimits() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) return;
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        assumeTrue(discovered.isPresent(), "bubblewrap/prlimit are required for Linux sandbox qualification");
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
    void allowPlanExplicitlySharesHostNetworkNamespace() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) return;
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        assumeTrue(discovered.isPresent());
        Path working = Files.createTempDirectory("minos-bwrap-allow-working-");
        Path run = Files.createTempDirectory("minos-bwrap-allow-run-");
        Path artifact = run.resolve("index.scip");
        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of("/bin/true"), working, Map.of(), artifact, Duration.ofSeconds(10));
        assertTrue(discovered.orElseThrow().sandboxPlan(plan, run, WorkerNetworkPolicy.ALLOW).command().contains("--share-net"));
    }
}
