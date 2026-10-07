package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 3 : une cible réexécutée sur le même runId ne garde pas le temporaire d'un provider interrompu. */
class ProcessIndexerExecutorPartialCleanupTest {

    @Test
    void staleIndexPartialFromAnInterruptedProviderIsRemovedBeforeReexecution(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path home = temp.resolve("home");
        UUID runId = UUID.randomUUID();
        Path runDirectory = Files.createDirectories(home.resolve("runs").resolve(runId.toString()).resolve("fake-provider"));
        Path partial = Files.writeString(runDirectory.resolve("index.partial.scip"), "half-written by a dead provider");
        Path marker = Files.writeString(runDirectory.getParent().resolve(".resumable"), "runId=" + runId + "\n");
        Path source = temp.resolve("Provider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class Provider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "fresh-scip");
                    }
                }
                """);
        Path generated = project.resolve("index.scip");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor("fake-provider", home,
                (request, directory) -> {
                    assertFalse(Files.exists(partial), "the stale partial must be gone before the provider starts");
                    return new IndexerProcessPlan(List.of(java, source.toString(), generated.toString()),
                            project, Map.of(), generated, Duration.ofMinutes(1));
                });
        IndexerDescriptor descriptor = new IndexerDescriptor("fake-provider", "1", "fake", Set.of(Language.JAVA),
                Set.of(), Set.of(), IndexerQualification.QUALIFIED, 1, List.of());
        IndexingExecutionRequest request = new IndexingExecutionRequest(runId, UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());

        IndexingArtifact artifact = executor.execute(request);

        assertEquals("fresh-scip", Files.readString(artifact.finalArtifact()));
        assertFalse(Files.exists(partial));
        assertTrue(Files.isRegularFile(marker), "the executor never touches the resumable marker");
    }
}
