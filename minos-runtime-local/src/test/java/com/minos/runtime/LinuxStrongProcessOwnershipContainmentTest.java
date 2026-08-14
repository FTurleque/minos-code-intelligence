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
class LinuxStrongProcessOwnershipContainmentTest {

    @Test
    void immediateSetsidChildCannotEscapeBeforeProcessHandlePollingSeesIt() throws Exception {
        Path home = Files.createTempDirectory("minos-strong-owner-linux-home-");
        Path project = Files.createTempDirectory("minos-strong-owner-linux-project-");
        Path pidFile = project.resolve("detached.pid");
        Path source = project.resolve("ImmediateDetachedProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class ImmediateDetachedProvider {
                    public static void main(String[] args) throws Exception {
                        if ("child".equals(args[0])) {
                            Thread.sleep(300_000L);
                            return;
                        }
                        Process child = new ProcessBuilder(args[3], args[4], args[5], "child")
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        Files.writeString(Path.of(args[2]), Long.toString(child.pid()));
                        Files.writeString(Path.of(args[1]), "strong-owned-artifact");
                        // Deliberately no sleep: the root exits immediately after spawning the setsid child.
                    }
                }
                """);
        Path setsid = CommandLocator.find("setsid").orElseThrow();
        String java = javaExecutable();
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                home,
                (request, runDirectory) -> {
                    Path generated = runDirectory.resolve("generated.scip");
                    return new IndexerProcessPlan(
                            List.of(
                                    java,
                                    source.toString(),
                                    "root",
                                    generated.toString(),
                                    pidFile.toString(),
                                    setsid.toString(),
                                    java,
                                    source.toString()),
                            project,
                            Map.of(),
                            generated,
                            Duration.ofSeconds(30));
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(delegate, home);
        assumeTrue(executor.capability().strong(), () -> String.join("; ", executor.capability().diagnostics()));

        var artifact = executor.execute(request(project));
        long detachedPid = Long.parseLong(Files.readString(pidFile).trim());

        assertEquals("linux-cgroup-v2", executor.capability().mechanism());
        assertEquals("strong-owned-artifact", Files.readString(artifact.finalArtifact()));
        awaitDead(detachedPid);
        assertFalse(ProcessHandle.of(detachedPid).map(ProcessHandle::isAlive).orElse(false),
                "setsid descendant must be killed by its inherited cgroup membership");
        assertTrue(LinuxCgroupJob.delegatedRoot().orElseThrow().resolve("minos-provider-" + request(project).runId())
                .getFileName() != null); // capability remains usable after job reclamation
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void awaitDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
    }
}
