package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.ExecutionPathAuthorization;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fault-injection coverage for the final filesystem identity check before provider spawn. */
@EnabledOnOs(OS.LINUX)
class PreLaunchPathAuthorizationTest {

    @TempDir
    Path temporary;

    @Test
    void unchangedAuthorizedScopeStillExecutes() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        IndexingExecutionRequest request = request(project, module, Path.of("module"));
        ProcessIndexerExecutor executor = executor(request);

        var artifact = executor.executeSandboxed(request, (plan, runDirectory) -> plan);

        assertEquals("fresh-scip", Files.readString(artifact.finalArtifact()));
    }

    @Test
    void scopeSymlinkRetargetedAfterPlanTransformFailsClosedBeforeSpawn() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path internal = Files.createDirectories(project.resolve("internal-module"));
        Path outside = Files.createDirectories(temporary.resolve("outside-module"));
        Path moduleLink = project.resolve("module");
        Files.createSymbolicLink(moduleLink, internal);
        IndexingExecutionRequest request = request(project, moduleLink, Path.of("module"));
        Path marker = temporary.resolve("provider-started");
        ProcessIndexerExecutor executor = executor(request, marker);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request, (plan, runDirectory) -> {
                    Files.delete(moduleLink);
                    Files.createSymbolicLink(moduleLink, outside);
                    return plan;
                }));

        assertTrue(failure.getMessage().contains("identity changed after canonical authorization"));
        assertFalse(Files.exists(marker), "provider must not start after scope retargeting");
    }

    @Test
    void registeredRootAndScopeRetargetedTogetherStillFailClosed() throws Exception {
        Path originalProject = Files.createDirectories(temporary.resolve("original-project"));
        Files.createDirectories(originalProject.resolve("module"));
        Path replacementProject = Files.createDirectories(temporary.resolve("replacement-project"));
        Files.createDirectories(replacementProject.resolve("module"));
        Path registeredLink = temporary.resolve("project");
        Files.createSymbolicLink(registeredLink, originalProject);
        Path module = registeredLink.resolve("module");
        IndexingExecutionRequest request = request(registeredLink, module, Path.of("module"));
        Path marker = temporary.resolve("provider-started-together");
        ProcessIndexerExecutor executor = executor(request, marker);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request, (plan, runDirectory) -> {
                    Files.delete(registeredLink);
                    Files.createSymbolicLink(registeredLink, replacementProject);
                    return plan;
                }));

        assertTrue(failure.getMessage().contains("identity changed after canonical authorization"));
        assertFalse(Files.exists(marker),
                "retargeting current root and current scope together must not launder authorization");
    }

    @Test
    void sameCanonicalPathReplacementIsRejectedByFilesystemIdentity() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        IndexingExecutionRequest request = request(project, module, Path.of("module"));
        assertTrue(request.pathAuthorization().orElseThrow().projectFileKey().isPresent(),
                "qualified Linux filesystem must expose a file identity for this invariant test");
        Path marker = temporary.resolve("provider-started-replacement");
        ProcessIndexerExecutor executor = executor(request, marker);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request, (plan, runDirectory) -> {
                    Files.move(module, project.resolve("module-original"));
                    Files.createDirectory(module);
                    return plan;
                }));

        assertTrue(failure.getMessage().contains("identity changed after canonical authorization"));
        assertFalse(Files.exists(marker),
                "a different inode/file identity at the same canonical pathname must be rejected");
    }

    @Test
    void incompleteFilesystemIdentityFailsClosedEvenWhenCanonicalPathsMatch() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        ExecutionPathAuthorization incomplete = new ExecutionPathAuthorization(
                project.toRealPath(),
                module.toRealPath(),
                Optional.empty(),
                Optional.empty());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> incomplete.verifyCurrent(project, module));

        assertTrue(failure.getMessage().contains("identity changed after canonical authorization"));
    }

    @Test
    void absentStrictAuthorizationFailsClosedBeforeSpawn() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        IndexingExecutionRequest request = new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                project,
                module,
                Path.of("module"),
                selection(),
                IndexingMode.FULL,
                List.of(),
                Optional.empty());
        Path marker = temporary.resolve("provider-started-without-authorization");
        ProcessIndexerExecutor executor = executor(request, marker);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request, (plan, runDirectory) -> plan));

        assertTrue(failure.getMessage().contains("not canonically authorized before launch"));
        assertFalse(Files.exists(marker),
                "provider must not start when a stable filesystem identity could not be authorized");
    }

    private ProcessIndexerExecutor executor(IndexingExecutionRequest request) {
        return executor(request, null);
    }

    private ProcessIndexerExecutor executor(IndexingExecutionRequest request, Path marker) {
        return new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("home-" + UUID.randomUUID()),
                (ignored, runDirectory) -> {
                    Path generated = runDirectory.resolve("generated.scip");
                    List<String> command = marker == null
                            ? List.of("/bin/sh", "-c", "printf fresh-scip > \"$1\"", "minos", generated.toString())
                            : List.of("/bin/sh", "-c",
                                    "printf started > \"$1\"; printf fresh-scip > \"$2\"",
                                    "minos", marker.toString(), generated.toString());
                    return new IndexerProcessPlan(
                            command,
                            request.projectRoot(),
                            Map.of(),
                            generated,
                            Duration.ofMinutes(1));
                });
    }

    private static IndexingExecutionRequest request(Path registeredRoot, Path projectRoot, Path relativeRoot) {
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                registeredRoot,
                projectRoot,
                relativeRoot,
                selection(),
                IndexingMode.FULL,
                List.of());
    }

    private static IndexerSelection selection() {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexerSelection(Language.JAVA, descriptor);
    }
}
