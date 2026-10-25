package com.minos.runtime.local.process;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexArtifactLimits;
import com.minos.runtime.local.UndeletableArtifactProviderMain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The executor names a provider artifact or a run directory by its leaf name, never by its absolute path. */
class ProcessIndexerExecutorRefusalMessageTest {

    private static final String PROVIDER = "fixture-provider";

    @TempDir
    Path temp;

    @Test
    void anEmptyProviderArtifactIsRefusedByItsFileName() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path generated = project.resolve("out").resolve("index.scip");
        ProcessIndexerExecutor executor = executor(project, generated, "empty");

        IndexingExecutionRequest indexing = request(project);

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> UnboundedProcessForTests.run(executor, indexing));

        assertEquals("provider did not produce a valid SCIP artifact: size=0/"
                + IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES + " file=index.scip", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aMissingProviderArtifactIsRefusedByItsFileName() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path generated = project.resolve("out").resolve("index.scip");
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, temp.resolve("home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        command(project.resolve("elsewhere.scip"), "empty"), project, Map.of(), generated,
                        Duration.ofSeconds(30)));

        IndexingExecutionRequest indexing = request(project);

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> UnboundedProcessForTests.run(executor, indexing));

        assertEquals("provider did not produce a valid SCIP artifact: index.scip", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aMissingWorkingDirectoryIsRefusedByItsName() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path missing = temp.resolve("missing-workdir");
        Path generated = project.resolve("out").resolve("index.scip");
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, temp.resolve("home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        command(generated, null), missing, Map.of(), generated, Duration.ofSeconds(30)));

        IndexingExecutionRequest indexing = request(project);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> UnboundedProcessForTests.run(executor, indexing));

        assertEquals("provider working directory is missing: missing-workdir", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aCleanupFailureAfterASuccessfulRunNamesOnlyLeafNames() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root deletes an entry of a directory without the write bit");
        Path project = Files.createDirectories(temp.resolve("project"));
        Path output = project.resolve("out");
        Path generated = output.resolve("index.scip");
        ProcessIndexerExecutor executor = executor(project, generated, null);
        try {
            IndexingExecutionRequest indexing = request(project);
            IOException refusal = assertThrows(IOException.class, () -> UnboundedProcessForTests.run(executor, indexing));

            assertEquals("MINOS post-execution cleanup failed for run directory " + PROVIDER
                    + "; a preserved provider artifact backup may remain as preexisting-artifact.scip",
                    refusal.getMessage());
            assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
            assertTrue(refusal.getSuppressed().length > 0, "the cleanup failure is attached, not swallowed");
        } finally {
            makeDeletable(output, generated);
        }
    }

    private static void makeDeletable(Path output, Path generated) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        } else if (Files.exists(generated)) {
            Files.setAttribute(generated, "dos:readonly", false);
        }
    }

    private ProcessIndexerExecutor executor(Path project, Path generated, String mode) {
        return new ProcessIndexerExecutor(PROVIDER, temp.resolve("home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        command(generated, mode), project, Map.of(), generated, Duration.ofSeconds(30)));
    }

    private static List<String> command(Path artifact, String mode) {
        String executable = CommandLocator.isWindows() ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        List<String> command = new ArrayList<>(List.of(
                java.toString(), "-cp", System.getProperty("java.class.path"),
                UndeletableArtifactProviderMain.class.getName(), artifact.toString()));
        if (mode != null) command.add(mode);
        return List.copyOf(command);
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                PROVIDER, "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
