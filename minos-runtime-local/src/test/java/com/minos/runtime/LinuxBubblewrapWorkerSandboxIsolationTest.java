package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapWorkerSandboxIsolationTest {

    @TempDir
    Path temporary;

    @Test
    void sandboxPlanNeverBindsWholeHostRoot() throws Exception {
        Path executable = Path.of("/bin/true");
        LinuxBubblewrapWorkerSandboxBackend backend =
                new LinuxBubblewrapWorkerSandboxBackend(executable, executable);
        Path working = Files.createDirectory(temporary.resolve("workspace"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path artifact = working.resolve("index.scip");
        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of(executable.toString()), working, Map.of(), artifact, Duration.ofSeconds(10));

        List<String> command = backend.sandboxPlan(plan, run, WorkerNetworkPolicy.DENY).command();
        for (int index = 0; index + 2 < command.size(); index++) {
            assertFalse("--ro-bind".equals(command.get(index))
                    && "/".equals(command.get(index + 1))
                    && "/".equals(command.get(index + 2)));
        }
        assertTrue(command.contains("--unshare-all"));
        assertTrue(backend.qualification().limitations().contains("LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST"));
    }
}
