package com.minos.runtime.local.sandbox;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.local.OwnershipOnlyExecutorsForTests;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Through the real chain {@code ProcessIndexerExecutor -> Linux sandbox -> provider}: a provider reads a declared
 * read-only root, cannot write into it, and leaves it unchanged.
 */
@EnabledOnOs(OS.LINUX)
class LinuxSandboxReadOnlyRootExecutionTest {

    @TempDir Path temporary;

    @Test
    void aProviderReadsADeclaredRootButCannotWriteIntoIt() throws Exception {
        LinuxBubblewrapWorkerSandboxBackend backend = SandboxTestSupport.requireBackend(
                LinuxBubblewrapWorkerSandboxBackend.discover(), "a qualified Linux sandbox backend is required");
        Path repository = Files.createDirectories(temporary.resolve("repository/org/example"));
        Path root = repository.getParent().getParent();
        Files.writeString(repository.resolve("marker.txt"), "artifact-bytes");
        List<String> before = listing(root);
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path home = Files.createDirectories(temporary.resolve("home"));

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor("fake-provider", home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        // The root is named as a directory only: arbitrary directories are never promoted to
                        // read roots, so the provider reaches its content through the declared mount alone.
                        List.of("/bin/sh", "-c",
                                "cat \"$1/org/example/marker.txt\" > \"$2/copy.txt\"; "
                                        + "if touch \"$1/written.txt\" 2>/dev/null; then echo written; "
                                        + "else echo refused; fi > \"$2/write.txt\"",
                                "provider", root.toString(), project.toString()),
                        project, Map.of(), runDirectory.resolve("index.scip"), Duration.ofSeconds(30),
                        List.of(root), List.of()));

        assertThrows(Exception.class, () -> backend.execute(executor, request(project), WorkerNetworkPolicy.DENY),
                "the provider produces no artifact");

        assertEquals("artifact-bytes", Files.readString(project.resolve("copy.txt")).trim(),
                "the provider read the declared root");
        assertEquals("refused", Files.readString(project.resolve("write.txt")).trim(),
                "the write into the root failed inside the sandbox");
        assertFalse(Files.exists(root.resolve("written.txt")), "nothing was written into the root");
        assertEquals(before, listing(root), "the root is unchanged");
    }

    @Test
    void cgroupOwnershipWithoutAnOsSandboxRefusesAPlanThatDeclaresARoot() throws Exception {
        Path repository = Files.createDirectories(temporary.resolve("repository"));
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path started = project.resolve("started.txt");
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor("fake-provider", home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of("/bin/sh", "-c", "touch \"$1\"", "provider", started.toString()),
                        project, Map.of(), runDirectory.resolve("index.scip"), Duration.ofSeconds(30),
                        List.of(repository), List.of()));
        StrongProcessOwnershipIndexerExecutor executor = OwnershipOnlyExecutorsForTests.ownershipOnly(delegate, home);
        SandboxTestSupport.requireStrongCapability(executor.capability().strong(), executor.capability().diagnostics());

        IndexingExecutionRequest request = request(project);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> executor.execute(request));

        assertTrue(refused.getMessage().contains("without an OS sandbox"), refused.getMessage());
        assertFalse(Files.exists(started), "no provider process was started");
    }

    private static List<String> listing(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.map(path -> root.relativize(path) + ":" + path.toFile().lastModified()).sorted().toList();
        }
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
