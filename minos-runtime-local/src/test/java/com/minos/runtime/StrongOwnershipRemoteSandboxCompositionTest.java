package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongOwnershipRemoteSandboxCompositionTest {

    @Test
    void qualifiedRemoteSandboxCanControlStrongOwnershipWrapperWithoutNestedStandaloneBoundary(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.writeString(project.resolve("source.scip"), "fixture");
        Path copier = temp.resolve("CopyFixture.java");
        Files.writeString(copier, """
                import java.nio.file.*;
                class CopyFixture {
                    public static void main(String[] args) throws Exception {
                        Files.copy(Path.of(args[0]), Path.of(args[1]), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                """);
        ProcessIndexerExecutor raw = new ProcessIndexerExecutor(
                "fixture-provider",
                temp.resolve("home"),
                (request, runDirectory) -> {
                    Path artifact = runDirectory.resolve("generated.scip");
                    return new IndexerProcessPlan(
                            List.of(javaExecutable().toString(), copier.toString(), source.toString(), artifact.toString()),
                            request.projectRoot(),
                            Map.of(),
                            artifact,
                            Duration.ofSeconds(20));
                });
        AtomicBoolean standaloneBoundaryUsed = new AtomicBoolean();
        StrongProcessOwnershipIndexerExecutor strong = new StrongProcessOwnershipIndexerExecutor(
                raw,
                new StrongProcessOwnershipIndexerExecutor.BoundaryProvider() {
                    @Override
                    public StrongProcessOwnershipIndexerExecutor.Capability capability() {
                        return StrongProcessOwnershipIndexerExecutor.Capability.available("fixture-standalone");
                    }

                    @Override
                    public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                        standaloneBoundaryUsed.set(true);
                        throw new AssertionError("remote sandbox must replace, not nest, standalone ownership");
                    }
                });

        assertTrue(strong instanceof ProcessSandboxCapableIndexerExecutor);
        IndexingExecutionRequest request = request(project);
        var artifact = ((ProcessSandboxCapableIndexerExecutor) strong).executeSandboxed(
                request, (plan, runDirectory) -> plan);

        assertTrue(Files.isRegularFile(artifact.finalArtifact()));
        assertFalse(standaloneBoundaryUsed.get());
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture-provider", "1", "fixture", Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of("TEST"));
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL, List.of());
    }
}
