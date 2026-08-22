package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Assumptions;
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
    void processMetadataNeverPersistsProviderCommandArguments() throws Exception {
        Path project = temporary.resolve("metadata-project");
        Files.createDirectories(project);
        Path generated = project.resolve("index.scip");
        Path source = temporary.resolve("MetadataProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class MetadataProvider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "fresh-scip");
                    }
                }
                """);
        String apiKey = "api-key-value-must-not-leak";
        String bearer = "bearer-value-must-not-leak";
        String credential = "credential-value-must-not-leak";
        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("metadata-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(
                                java,
                                source.toString(),
                                generated.toString(),
                                "--api-key",
                                apiKey,
                                "authorization=Bearer " + bearer,
                                "credential=" + credential),
                        project,
                        Map.of(),
                        generated,
                        Duration.ofMinutes(1)));

        var artifact = executor.execute(request(project));
        String metadata = Files.readString(artifact.finalArtifact().getParent().resolve("process.txt"));

        assertTrue(metadata.contains("command=<redacted provider command; argumentCount=6>"));
        assertFalse(metadata.contains(apiKey));
        assertFalse(metadata.contains(bearer));
        assertFalse(metadata.contains(credential));
        assertFalse(metadata.contains("--api-key"),
                "provider option names are not a safe allow-list for persisted diagnostics");
    }

    @Test
    void replacingDiagnosticPathsWithSymlinksCannotRedirectMinosHostWrites() throws Exception {
        Assumptions.assumeFalse(CommandLocator.isWindows(), "symbolic-link fixture is qualified on Unix CI");
        Path project = temporary.resolve("symlink-project");
        Files.createDirectories(project);
        Path outsideStdout = temporary.resolve("outside-stdout.txt");
        Path outsideMetadata = temporary.resolve("outside-metadata.txt");
        Files.writeString(outsideStdout, "stdout-safe");
        Files.writeString(outsideMetadata, "metadata-safe");
        Path source = temporary.resolve("SymlinkProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class SymlinkProvider {
                    public static void main(String[] args) throws Exception {
                        Path stdout = Path.of(args[0]);
                        Path stdoutTarget = Path.of(args[1]).toAbsolutePath();
                        Path metadata = Path.of(args[2]);
                        Path metadataTarget = Path.of(args[3]).toAbsolutePath();
                        Path artifact = Path.of(args[4]);
                        Files.deleteIfExists(stdout);
                        Files.createSymbolicLink(stdout, stdoutTarget);
                        Files.deleteIfExists(metadata);
                        Files.createSymbolicLink(metadata, metadataTarget);
                        System.out.print("provider-output");
                        System.out.flush();
                        Files.writeString(artifact, "fresh-scip");
                    }
                }
                """);

        String java = javaExecutable();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("symlink-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(
                                java,
                                source.toString(),
                                runDirectory.resolve("provider.stdout.log").toString(),
                                outsideStdout.toString(),
                                runDirectory.resolve("process.txt").toString(),
                                outsideMetadata.toString(),
                                runDirectory.resolve("generated.scip").toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("generated.scip"),
                        Duration.ofMinutes(1)));

        var artifact = executor.execute(request(project));
        Path runDirectory = artifact.finalArtifact().getParent();

        assertEquals("fresh-scip", Files.readString(artifact.finalArtifact()));
        assertEquals("stdout-safe", Files.readString(outsideStdout),
                "provider stdout must stay bound to the pre-opened MINOS descriptor");
        assertEquals("metadata-safe", Files.readString(outsideMetadata),
                "post-exit metadata appends must stay bound to the pre-opened MINOS descriptor");
        assertTrue(Files.isSymbolicLink(runDirectory.resolve("provider.stdout.log")),
                "fixture must prove the provider replaced the visible stdout pathname");
        assertTrue(Files.isSymbolicLink(runDirectory.resolve("process.txt")),
                "fixture must prove the provider replaced the visible metadata pathname");
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
