package com.minos.bootstrap.orchestration;

import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 lot 5 (ADR 0039) : validation de bout en bout. Une JVM fille indexe, est tuée par
 * {@code destroyForcibly()} au milieu de la phase provider après deux points de contrôle, une
 * nouvelle JVM reprend : les providers déjà terminés ne sont pas réexécutés et l'index final est
 * identique octet pour octet à celui d'un run complet dans une JVM sans interruption.
 */
class ResumeAfterHardKillIntegrationTest {

    private static final long CHILD_TIMEOUT_SECONDS = 120L;

    @Test
    void killedIndexingIsResumedInANewJvmWithoutReexecutingCompletedProviders(@TempDir Path temp) throws Exception {
        Path project = project(temp);
        Path fullHome = Files.createDirectories(temp.resolve("home-full"));
        Path crashHome = Files.createDirectories(temp.resolve("home-crash"));

        assertEquals(0, runToCompletion("full", fullHome, project), "reference run must succeed");
        byte[] fullIndex = Files.readAllBytes(fullHome.resolve("active.bin"));
        assertEquals(List.of("ui/app", "ui/lib", "ui/web"), Files.readAllLines(fullHome.resolve("executions.log")));

        crashMidProvider(crashHome, project);
        assertEquals(List.of("ui/app", "ui/lib"), Files.readAllLines(crashHome.resolve("executions.log")),
                "two providers completed before the kill");

        FileIndexStateStore store = new FileIndexStateStore(crashHome.resolve("index-state"));
        UUID projectId = UUID.fromString(ResumeCrashFixtureMain.PROJECT_ID);
        List<IndexingRun> runs = store.listRuns(projectId);
        assertEquals(1, runs.size());
        IndexingRun killed = runs.getFirst();
        assertEquals(IndexingRun.Status.RUNNING, killed.status(), "a hard kill leaves the run RUNNING on disk");
        assertEquals(2, killed.executions().size());
        assertTrue(killed.executions().stream().allMatch(execution -> execution.checkpoint().isPresent()),
                "both checkpoints were persisted durably before the kill");
        if (isWindows()) {
            // Windows variant: DurableAtomicFile cannot fsync directories there; the run trace,
            // the artifacts and their digest sidecars must still be readable after the kill.
            for (IndexingRun.IndexerExecution execution : killed.executions()) {
                assertTrue(Files.isRegularFile(execution.finalArtifact()), "artifact survives without directory fsync");
            }
        }

        assertEquals(0, runToCompletion("resume", crashHome, project), "resumed run must succeed");

        assertEquals(List.of("ui/app", "ui/lib", "ui/web"), Files.readAllLines(crashHome.resolve("executions.log")),
                "only the missing provider ran in the new JVM");
        assertArrayEquals(fullIndex, Files.readAllBytes(crashHome.resolve("active.bin")),
                "the resumed index is byte-for-byte the index of a full run");
        List<String> result = Files.readAllLines(crashHome.resolve("result-resume.txt"));
        assertEquals(killed.id().toString(), result.get(0), "the same run id was reopened");
        assertEquals("SUCCEEDED", result.get(1));
        assertTrue(result.get(2).contains("attempt=2") && result.get(2).contains("reusedTargets=2"), result.get(2));
        IndexingRun resumed = store.findRun(killed.id()).orElseThrow();
        assertEquals(IndexingRun.Status.SUCCEEDED, resumed.status());
        assertEquals(3, resumed.executions().size());
        assertFalse(Files.exists(crashHome.resolve("runs").resolve(killed.id().toString()).resolve(".resumable")),
                "the marker is removed once the run is terminal");
        assertEquals(ProjectIndexState.Availability.READY, store.findProjectState(projectId).orElseThrow().availability());
    }

    @Test
    void windowsVariantRecoversTheKilledRunWithoutDirectoryFsync(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(isWindows(), "this variant documents the Windows durability model");
        Path project = project(temp);
        Path home = Files.createDirectories(temp.resolve("home"));
        crashMidProvider(home, project);

        // The reconciler (new JVM) must classify the killed run as INTERRUPTED from what reached disk.
        assertEquals(0, runToCompletion("resume", home, project));
        assertEquals(List.of("ui/app", "ui/lib", "ui/web"), Files.readAllLines(home.resolve("executions.log")));
    }

    /** Runs a child to completion; the child is destroyed whatever happens (V26), every wait is bounded. */
    private static int runToCompletion(String mode, Path home, Path project) throws Exception {
        Process child = runChild(mode, home, project);
        try {
            assertTrue(child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "child " + mode + " must exit in time");
            return child.exitValue();
        } finally {
            reap(child);
        }
    }

    /** Starts the crashing child, waits for its sentinel and kills it; destroyed even when the wait fails. */
    private static void crashMidProvider(Path home, Path project) throws Exception {
        Process crashing = runChild("crash", home, project);
        try {
            awaitSentinel(home.resolve("sentinel"), crashing);
        } finally {
            reap(crashing);
        }
        assertFalse(crashing.isAlive(), "the killed JVM must be gone");
    }

    private static void reap(Process child) throws InterruptedException {
        child.destroyForcibly();
        if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("child JVM survived destroyForcibly()");
        }
    }

    private static Path project(Path temp) throws IOException {
        Path root = Files.createDirectories(temp.resolve("project"));
        for (Path scope : ResumeCrashFixtureMain.SCOPES) {
            Files.createDirectories(root.resolve(scope).resolve("src"));
            Files.writeString(root.resolve(scope).resolve("src/index.ts"),
                    "export const v = '" + scope.toString().replace('\\', '/') + "';");
        }
        Files.writeString(root.resolve("package.json"), "{}");
        return root;
    }

    private static Process runChild(String mode, Path home, Path project) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        // -Dminos.test.resumePolicy=NO_RESUME reproduces the pre-R1 behaviour (red proof of this test).
        String policy = System.getProperty("minos.test.resumePolicy", IndexingResumePolicy.RESUME.name());
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                ResumeCrashFixtureMain.class.getName(), mode, home.toString(), project.toString(), policy);
        builder.redirectErrorStream(true);
        builder.redirectOutput(home.resolve("child-" + mode + ".log").toFile());
        return builder.start();
    }

    private static void awaitSentinel(Path sentinel, Process child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHILD_TIMEOUT_SECONDS);
        while (!Files.exists(sentinel)) {
            if (!child.isAlive()) throw new AssertionError("child exited before the sentinel: exit=" + child.exitValue());
            if (System.nanoTime() > deadline) throw new AssertionError("child never reached the sentinel");
            Thread.sleep(50L);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
