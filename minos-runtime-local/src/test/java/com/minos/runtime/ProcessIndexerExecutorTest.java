package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class ProcessIndexerExecutorTest {

    @TempDir
    Path temporary;

    @Test
    void executesARealChildProcessAndPreservesPreexistingArtifact() throws Exception {
        Path project = temporary.resolve("project");
        Files.createDirectories(project);
        Path generated = project.resolve("index.scip");
        Files.writeString(generated, "previous");
        Path source = temporary.resolve("Provider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class Provider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "fresh-scip");
                    }
                }
                """);

        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(java, source.toString(), generated.toString()),
                        project,
                        Map.of(),
                        generated,
                        Duration.ofMinutes(1)
                )
        );

        IndexingExecutionRequest request = request(project);

        var artifact = executor.execute(request);

        assertEquals("previous", Files.readString(generated));
        assertEquals("fresh-scip", Files.readString(artifact.finalArtifact()));
        assertTrue(Files.isRegularFile(artifact.finalArtifact().getParent().resolve("provider.stdout.log")));
        assertTrue(Files.isRegularFile(artifact.finalArtifact().getParent().resolve("provider.stderr.log")));
    }

    @Test
    void normalProviderExitCleansAPreviouslyObservedDetachedChild() throws Exception {
        Path project = temporary.resolve("orphan-project");
        Files.createDirectories(project);
        Path generated = project.resolve("index.scip");
        Path childPidFile = temporary.resolve("provider-child.pid");
        Path source = temporary.resolve("DetachedProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class DetachedProvider {
                    public static void main(String[] args) throws Exception {
                        if ("child".equals(args[0])) {
                            Thread.sleep(300_000L);
                            return;
                        }
                        Process child = new ProcessBuilder(args[4], args[1], "child")
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        Files.writeString(Path.of(args[3]), Long.toString(child.pid()));
                        Files.writeString(Path.of(args[2]), "fresh-scip");
                        Thread.sleep(1_000L);
                    }
                }
                """);

        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("orphan-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(java, source.toString(), "root", source.toString(), generated.toString(),
                                childPidFile.toString(), java),
                        project,
                        Map.of(),
                        generated,
                        Duration.ofMinutes(1)
                )
        );

        var artifact = executor.execute(request(project));
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());

        assertEquals("fresh-scip", Files.readString(artifact.finalArtifact()));
        awaitDead(childPid);
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "a provider-owned child must not survive a successful root exit");
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
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private static void awaitDead(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
    }
}
