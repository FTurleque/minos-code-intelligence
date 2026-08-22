package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial regression coverage for the preexisting-provider-artifact preservation boundary in
 * {@link ProcessIndexerExecutor}: a hostile provider must never be able to make MINOS silently lose
 * or permanently strand an artifact that existed at the generated-artifact path before the provider
 * ran, regardless of what the provider does to that path or how it exits.
 */
class ProcessIndexerExecutorArtifactPreservationTest {

    private static final String PROVIDER = "fixture-provider";

    @Test
    void hostileProviderReplacingArtifactWithNonEmptyDirectoryStillRestoresPreexistingArtifact(
            @TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path generated = project.resolve("index.scip");
        Files.writeString(generated, "previous", StandardCharsets.UTF_8);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER, home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        directoryReplacingCommand(generated, true, 0, false),
                        project, Map.of(), generated, Duration.ofSeconds(30)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.execute(request(project)));
        assertTrue(failure.getMessage().contains("valid SCIP artifact"), failure.getMessage());

        assertEquals("previous", Files.readString(generated),
                "the preexisting artifact must be restored even though cleanup had to contend with"
                        + " a hostile non-empty directory at the same path");
        assertTrue(hasQuarantinedSibling(project, "index.scip"),
                "the hostile directory content must be quarantined, not silently discarded");
    }

    @Test
    void hostileProviderReplacingArtifactWithEmptyDirectoryStillRestoresPreexistingArtifact(
            @TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path generated = project.resolve("index.scip");
        Files.writeString(generated, "previous", StandardCharsets.UTF_8);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER, home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        directoryReplacingCommand(generated, false, 0, false),
                        project, Map.of(), generated, Duration.ofSeconds(30)));

        assertThrows(IllegalStateException.class, () -> executor.execute(request(project)));

        assertEquals("previous", Files.readString(generated),
                "an empty hostile directory must not block restoration either");
    }

    @Test
    void hostileProviderFailureWithNonEmptyDirectoryStillRestoresPreexistingArtifact(
            @TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path generated = project.resolve("index.scip");
        Files.writeString(generated, "previous", StandardCharsets.UTF_8);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER, home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        directoryReplacingCommand(generated, true, 7, false),
                        project, Map.of(), generated, Duration.ofSeconds(30)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.execute(request(project)));
        assertTrue(failure.getMessage().contains("exited with code 7"), failure.getMessage());

        assertEquals("previous", Files.readString(generated),
                "restoration must happen after a non-zero provider exit too, even with a hostile"
                        + " directory occupying the artifact path");
    }

    @Test
    void hostileProviderTimeoutWithNonEmptyDirectoryStillRestoresPreexistingArtifact(
            @TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path generated = project.resolve("index.scip");
        Files.writeString(generated, "previous", StandardCharsets.UTF_8);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER, home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        directoryReplacingCommand(generated, true, 0, true),
                        project, Map.of(), generated, Duration.ofMillis(1_500)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.execute(request(project)));
        assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());

        assertEquals("previous", Files.readString(generated),
                "restoration must happen after a timeout too, even with a hostile directory"
                        + " occupying the artifact path");
    }

    @Test
    void symlinkReplacingArtifactRestoresWithoutFollowingOrDeletingTheLinkTarget(
            @TempDir Path home) throws Exception {
        Assumptions.assumeFalse(CommandLocator.isWindows(), "symbolic-link fixture is qualified on Unix CI");
        Path project = Files.createDirectories(home.resolve("project"));
        Path generated = project.resolve("index.scip");
        Files.writeString(generated, "previous", StandardCharsets.UTF_8);
        Path outsideTarget = home.resolve("outside-target.txt");
        Files.writeString(outsideTarget, "must-not-be-touched", StandardCharsets.UTF_8);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER, home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        symlinkReplacingCommand(generated, outsideTarget),
                        project, Map.of(), generated, Duration.ofSeconds(30)));

        assertThrows(IllegalStateException.class, () -> executor.execute(request(project)));

        assertEquals("previous", Files.readString(generated),
                "the preexisting artifact must be restored after cleanup unlinks the hostile symlink");
        assertEquals("must-not-be-touched", Files.readString(outsideTarget),
                "cleanup must never follow the symlink and must never delete its target");
    }

    @Test
    void residueReclamationDoesNotDeleteARestoredArtifactOutsideTheDefaultLocation(
            @TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        UUID runId = UUID.randomUUID();
        Path runDirectory = home.resolve("runs").resolve(runId.toString()).resolve(PROVIDER);
        Path staged = runDirectory.resolve("staging").resolve("index.scip");
        Files.createDirectories(staged.getParent());
        Files.writeString(staged, "previous", StandardCharsets.UTF_8);

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER, home,
                (request, rd) -> new IndexerProcessPlan(
                        artifactCommand(staged),
                        project, Map.of(), staged, Duration.ofSeconds(30)));

        IndexingArtifact artifact = executor.executeSandboxed(
                request(runId, project),
                quotaTransformer(ProviderWriteQuota.DEFAULT));

        assertEquals("contained-artifact", Files.readString(artifact.finalArtifact()),
                "a normal successful run must still promote the provider's fresh output");
        assertEquals("previous", Files.readString(staged),
                "residue reclamation must not delete a top-level entry that cleanup just restored"
                        + " a preexisting artifact into");
        assertTrue(Files.isDirectory(staged.getParent()),
                "the staging directory itself must survive reclamation once it holds a restored artifact");
    }

    private static ProcessIndexerExecutor.ProcessPlanTransformer quotaTransformer(ProviderWriteQuota quota) {
        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) {
                return plan;
            }

            @Override
            public Optional<ProviderWriteQuota> providerWriteQuota() {
                return Optional.of(quota);
            }
        };
    }

    private static boolean hasQuarantinedSibling(Path directory, String originalName) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = String.valueOf(child.getFileName());
                if (name.startsWith(originalName + ".quarantined-") && Files.isDirectory(child)) {
                    return Files.exists(child.resolve("nested.txt"));
                }
            }
        }
        return false;
    }

    private static List<String> directoryReplacingCommand(
            Path artifact, boolean populate, int exitCode, boolean hang) {
        List<String> command = new ArrayList<>(javaCommand());
        command.add(DirectoryReplacingProviderMain.class.getName());
        command.add(artifact.toString());
        command.add(Boolean.toString(populate));
        command.add(Integer.toString(exitCode));
        command.add(Boolean.toString(hang));
        return List.copyOf(command);
    }

    private static List<String> symlinkReplacingCommand(Path artifact, Path target) {
        List<String> command = new ArrayList<>(javaCommand());
        command.add(SymlinkReplacingProviderMain.class.getName());
        command.add(artifact.toString());
        command.add(target.toString());
        return List.copyOf(command);
    }

    private static List<String> artifactCommand(Path artifact) {
        List<String> command = new ArrayList<>(javaCommand());
        command.add(CompliantProviderMain.class.getName());
        command.add(artifact.toString());
        return List.copyOf(command);
    }

    private static List<String> javaCommand() {
        String executable = CommandLocator.isWindows() ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"));
    }

    private static IndexingExecutionRequest request(Path project) {
        return request(UUID.randomUUID(), project);
    }

    private static IndexingExecutionRequest request(UUID runId, Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                PROVIDER, "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                runId, UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
