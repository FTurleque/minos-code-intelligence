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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
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

        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        IndexingExecutionRequest request = new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());

        var artifact = executor.execute(request);

        assertEquals("previous", Files.readString(generated));
        assertEquals("fresh-scip", Files.readString(artifact.finalArtifact()));
        assertTrue(Files.isRegularFile(artifact.finalArtifact().getParent().resolve("provider.stdout.log")));
        assertTrue(Files.isRegularFile(artifact.finalArtifact().getParent().resolve("provider.stderr.log")));
    }
}
