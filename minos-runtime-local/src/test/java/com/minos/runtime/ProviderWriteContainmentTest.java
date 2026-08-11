package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural proof that a hostile provider cannot fill the disk, cannot exhaust entries and cannot
 * poison {@code MINOS_HOME/runs} for the runs that follow it.
 */
class ProviderWriteContainmentTest {

    private static final String PROVIDER = "fixture-provider";

    @Test
    void aHostileProviderIsKilledDuringExecutionAndItsResidueIsReclaimed(@TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        UUID hostileRun = UUID.randomUUID();
        AtomicInteger kills = new AtomicInteger();

        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                PROVIDER,
                home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        hostileCommand(runDirectory, 40_000, 4_096, 120L),
                        project,
                        Map.of(),
                        runDirectory.resolve("index.scip"),
                        Duration.ofSeconds(120)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.executeSandboxed(
                        executionRequest(hostileRun, project),
                        containedTransformer(new ProviderWriteQuota(512L * 1024L, 256L, Duration.ofMillis(100L)),
                                kills)));

        assertTrue(failure.getMessage().contains("write containment breached"), failure.getMessage());
        assertTrue(kills.get() >= 1, "the OS job boundary must be destroyed on breach");

        Path runDirectory = home.resolve("runs").resolve(hostileRun.toString()).resolve(PROVIDER);
        assertTrue(Files.isDirectory(runDirectory));
        List<String> residue = entryNames(runDirectory);
        assertFalse(residue.contains("hostile-tree"), "provider residue must be reclaimed: " + residue);
        assertTrue(ProviderResidueReclamation.RETAINED_ENTRIES.containsAll(residue),
                "only MINOS diagnostics may survive a contained run: " + residue);
        assertTrue(residue.contains("process.txt"), "MINOS diagnostics must survive: " + residue);
    }

    @Test
    void theRunThatFollowsAHostileRunStartsNormally(@TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        AtomicInteger kills = new AtomicInteger();

        ProcessIndexerExecutor hostile = new ProcessIndexerExecutor(
                PROVIDER,
                home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        hostileCommand(runDirectory, 40_000, 4_096, 120L),
                        project,
                        Map.of(),
                        runDirectory.resolve("index.scip"),
                        Duration.ofSeconds(120)));
        assertThrows(IllegalStateException.class, () -> hostile.executeSandboxed(
                executionRequest(UUID.randomUUID(), project),
                containedTransformer(new ProviderWriteQuota(512L * 1024L, 256L, Duration.ofMillis(100L)), kills)));

        ProcessIndexerExecutor healthy = new ProcessIndexerExecutor(
                PROVIDER,
                home,
                (request, runDirectory) -> new IndexerProcessPlan(
                        artifactCommand(runDirectory.resolve("index.scip")),
                        project,
                        Map.of(),
                        runDirectory.resolve("index.scip"),
                        Duration.ofSeconds(120)));

        IndexingArtifact artifact = healthy.executeSandboxed(
                executionRequest(UUID.randomUUID(), project),
                containedTransformer(ProviderWriteQuota.DEFAULT, new AtomicInteger()));

        assertEquals(PROVIDER, artifact.indexerId());
        assertEquals("contained-artifact",
                Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
    }

    private static ProcessIndexerExecutor.ProcessPlanTransformer containedTransformer(
            ProviderWriteQuota quota,
            AtomicInteger kills
    ) {
        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) {
                return plan;
            }

            @Override
            public Optional<ProviderWriteQuota> providerWriteQuota() {
                return Optional.of(quota);
            }

            @Override
            public void killContainedJob() {
                kills.incrementAndGet();
            }
        };
    }

    private static List<String> entryNames(Path directory) throws Exception {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) names.add(String.valueOf(child.getFileName()));
        }
        return names;
    }

    private static List<String> hostileCommand(Path target, int files, int bytes, long aliveSeconds) {
        List<String> command = new ArrayList<>(javaCommand());
        command.add(HostileProviderMain.class.getName());
        command.add(target.toString());
        command.add(Integer.toString(files));
        command.add(Integer.toString(bytes));
        command.add(Long.toString(aliveSeconds));
        return List.copyOf(command);
    }

    private static List<String> artifactCommand(Path artifact) {
        List<String> command = new ArrayList<>(javaCommand());
        command.add(CompliantProviderMain.class.getName());
        command.add(artifact.toString());
        return List.copyOf(command);
    }

    private static List<String> javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"));
    }

    private static IndexingExecutionRequest executionRequest(UUID runId, Path projectRoot) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                PROVIDER,
                "1.0.0",
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        return new IndexingExecutionRequest(
                runId,
                UUID.randomUUID(),
                projectRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
