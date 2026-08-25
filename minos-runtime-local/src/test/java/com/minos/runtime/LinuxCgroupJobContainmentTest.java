package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial proof that the Linux job boundary is aggregate: a provider cannot multiply a limit by
 * forking, cannot outlive MINOS and cannot relax the boundary from inside the sandbox.
 */
@EnabledOnOs(OS.LINUX)
class LinuxCgroupJobContainmentTest {

    private static final Path SHELL = Path.of("/bin/sh");
    private static final long MEBIBYTE = 1024L * 1024L;

    @Test
    void theDelegatedCgroupAppliesAggregateMemoryProcessAndCpuLimits() throws Exception {
        Path root = requireDelegatedRoot();
        LinuxCgroupJob job = LinuxCgroupJob.create(
                root, "minos-limits-" + UUID.randomUUID(), new LinuxCgroupJob.Limits(64 * MEBIBYTE, 32L, 50_000L));
        try {
            assertEquals(Long.toString(64 * MEBIBYTE), read(job, "memory.max"));
            assertEquals("32", read(job, "pids.max"));
            assertEquals("50000 " + LinuxCgroupJob.CPU_PERIOD_MICROS, read(job, "cpu.max"));
        } finally {
            job.close();
        }
    }

    @Test
    void severalDescendantsCannotExceedTheAggregateMemoryLimitTogether() throws Exception {
        Path root = requireDelegatedRoot();
        assumeTrue(Files.isDirectory(Path.of("/dev/shm")), "/dev/shm is required to exercise aggregate memory");
        Path scratch = Path.of("/dev/shm", "minos-memory-" + UUID.randomUUID());
        Files.createDirectory(scratch);
        LinuxCgroupJob job = LinuxCgroupJob.create(
                root, "minos-memory-" + UUID.randomUUID(), new LinuxCgroupJob.Limits(64 * MEBIBYTE, 64L, 800_000L));
        try {
            // Neither writer alone exceeds memory.max: only their aggregate does.
            run(job, 120, "/bin/sh", "-c",
                    "dd if=/dev/zero of=\"$1/a\" bs=1M count=48 2>/dev/null &"
                            + " dd if=/dev/zero of=\"$1/b\" bs=1M count=48 2>/dev/null &"
                            + " wait",
                    "sh", scratch.toString());
            long materialized = 0L;
            try (var children = Files.list(scratch)) {
                for (Path child : children.toList()) materialized += Files.size(child);
            }
            assertTrue(materialized < 96 * MEBIBYTE,
                    "the aggregate memory limit must stop the forked tree: materialized=" + materialized);
        } finally {
            job.close();
            deleteQuietly(scratch);
        }
    }

    @Test
    void aForkingProviderCannotExceedTheAggregateProcessLimit() throws Exception {
        Path root = requireDelegatedRoot();
        LinuxCgroupJob job = LinuxCgroupJob.create(
                root, "minos-pids-" + UUID.randomUUID(), new LinuxCgroupJob.Limits(64 * MEBIBYTE, 4L, 800_000L));
        try {
            run(job, 60, "/bin/sh", "-c",
                    "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do sleep 1 & done; wait; exit 0");
            assertTrue(read(job, "pids.events").contains("max "), "pids.events must report the limit");
            assertTrue(firstEventCount(read(job, "pids.events")) > 0L,
                    "the aggregate process limit must have refused at least one fork");
        } finally {
            job.close();
        }
    }

    @Test
    void anAbusiveProviderIsThrottledByTheAggregateCpuLimit() throws Exception {
        Path root = requireDelegatedRoot();
        LinuxCgroupJob job = LinuxCgroupJob.create(
                root, "minos-cpu-" + UUID.randomUUID(), new LinuxCgroupJob.Limits(64 * MEBIBYTE, 32L, 10_000L));
        try {
            run(job, 120, "/bin/sh", "-c", "i=0; while [ $i -lt 400000 ]; do i=$((i+1)); done; exit 0");
            assertTrue(read(job, "cpu.stat").contains("nr_throttled"));
            assertTrue(throttledPeriods(read(job, "cpu.stat")) > 0L,
                    "the aggregate CPU limit must have throttled the tree");
        } finally {
            job.close();
        }
    }

    @Test
    void noDescendantSurvivesTheJobBoundary() throws Exception {
        Path root = requireDelegatedRoot();
        LinuxCgroupJob job = LinuxCgroupJob.create(
                root, "minos-survivors-" + UUID.randomUUID(), LinuxCgroupJob.Limits.DEFAULT);
        try {
            run(job, 30, "/bin/sh", "-c", "sleep 600 & sleep 600 & sleep 1; exit 0");
            assertTrue(job.aliveProcesses() >= 2, "the detached descendants must belong to the job");
            job.kill();
            assertEquals(0L, job.aliveProcesses(), "cgroup.kill must leave no surviving descendant");
        } finally {
            job.close();
        }
        assertFalse(Files.exists(job.directory()), "the sandbox cgroup must be reclaimed");
    }

    @Test
    void aJobDirectoryCanNeverEscapeTheDelegatedRoot() throws Exception {
        Path root = requireDelegatedRoot();
        assertThrows(IOException.class, () -> LinuxCgroupJob.create(root, "../escape", LinuxCgroupJob.Limits.DEFAULT));
        assertThrows(IOException.class, () -> LinuxCgroupJob.create(root, "  ", LinuxCgroupJob.Limits.DEFAULT));
    }

    @Test
    void theSandboxJoinsTheJobBeforeItExecutesAnyProviderCode(@TempDir Path temporary) throws Exception {
        Path root = requireDelegatedRoot();
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        assumeTrue(discovered.isPresent(), "a Linux sandbox backend is required");
        LinuxCgroupJob job = LinuxCgroupJob.create(
                root, "minos-plan-" + UUID.randomUUID(), LinuxCgroupJob.Limits.DEFAULT);
        try {
            Path working = Files.createDirectories(temporary.resolve("workspace"));
            Path run = Files.createDirectories(temporary.resolve("run"));
            IndexerProcessPlan plan = new IndexerProcessPlan(
                    List.of("/bin/true"), working, Map.of(), run.resolve("index.scip"), Duration.ofSeconds(10));

            List<String> command = discovered.orElseThrow()
                    .sandboxPlan(plan, run, WorkerNetworkPolicy.DENY, job).command();

            assertEquals(SHELL.toString(), command.get(0));
            assertEquals("-c", command.get(1));
            assertTrue(command.get(2).contains("cgroup.procs"), command.get(2));
            assertEquals(job.directory().toString(), command.get(3));
            assertTrue(command.indexOf("/bin/true") > 3, "provider code must come after the cgroup join");
        } finally {
            job.close();
        }
    }

    @Test
    void theQualifiedBackendDeclaresTheAggregateContainmentItReallyEnforces() {
        var discovered = LinuxBubblewrapWorkerSandboxBackend.discover();
        assumeTrue(discovered.isPresent(), "a Linux sandbox backend is required");
        WorkerSandboxQualification qualification = discovered.orElseThrow().qualification();

        assertTrue(qualification.containment().aggregateJobBoundaryEnforced());
        assertFalse(qualification.containment().hardFilesystemQuotaEnforced());
        assertFalse(qualification.containment().qualifiedForUntrustedCode());
        assertFalse(discovered.orElseThrow().supportsUntrustedCode());
        assertEquals(
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                qualification.trustDisposition());
        assertTrue(qualification.limitations()
                .contains("LINUX_CGROUP_V2_AGGREGATE_MEMORY_PIDS_CPU_JOB_BOUNDARY"));
        assertTrue(qualification.limitations()
                .contains("LINUX_PRLIMIT_PER_PROCESS_DEFENCE_IN_DEPTH_ONLY"));
        assertTrue(qualification.limitations().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_BYTES")));
        assertTrue(qualification.limitations().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_ENTRIES")));
    }

    private static Path requireDelegatedRoot() {
        Optional<Path> root = LinuxCgroupJob.delegatedRoot();
        assumeTrue(root.isPresent(),
                "a delegated cgroup v2 root is required; set " + LinuxCgroupJob.ROOT_ENVIRONMENT_VARIABLE);
        return root.orElseThrow();
    }

    private static void run(LinuxCgroupJob job, int timeoutSeconds, String... command) throws Exception {
        List<String> wrapped = job.enterThenExec(SHELL, List.of(command));
        Process process = new ProcessBuilder(wrapped).redirectErrorStream(true).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        process.getInputStream().readNBytes(8192);
    }

    private static String read(LinuxCgroupJob job, String file) throws IOException {
        return Files.readString(job.directory().resolve(file), StandardCharsets.UTF_8).trim();
    }

    private static long firstEventCount(String pidsEvents) {
        for (String line : pidsEvents.split("\n")) {
            if (line.startsWith("max ")) return Long.parseLong(line.substring(4).trim());
        }
        return 0L;
    }

    private static long throttledPeriods(String cpuStat) {
        for (String line : cpuStat.split("\n")) {
            if (line.startsWith("nr_throttled ")) return Long.parseLong(line.substring(13).trim());
        }
        return 0L;
    }

    private static void deleteQuietly(Path directory) {
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) Files.deleteIfExists(child);
        } catch (IOException ignored) {
            // Best effort cleanup of the shared memory scratch used by the adversarial test.
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Best effort cleanup of the shared memory scratch used by the adversarial test.
        }
    }
}
