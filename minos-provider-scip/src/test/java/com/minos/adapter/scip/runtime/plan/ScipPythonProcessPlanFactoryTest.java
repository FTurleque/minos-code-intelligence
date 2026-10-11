package com.minos.adapter.scip.runtime.plan;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.IndexerProcessPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipPythonProcessPlanFactoryTest {

    @TempDir
    Path temporaryDirectory;

    private Path pythonProject(String sourceName) throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("shop-backend"));
        Files.writeString(project.resolve(sourceName), "print('hello')\n");
        return project;
    }

    @Test
    void indexesTheProjectFromItsOwnRootWithAFixedNonInteractiveCommand() throws Exception {
        Path project = pythonProject("app.py");
        Path run = temporaryDirectory.resolve("run");

        IndexerProcessPlan plan = new ScipPythonProcessPlanFactory(List.of("node", "--require", "preload.cjs", "main.js"))
                .create(request(project, IndexingMode.FULL, List.of()), run);

        Path root = project.toAbsolutePath().normalize();
        Path output = run.toAbsolutePath().normalize().resolve("index.scip");
        assertEquals(List.of("node", "--require", "preload.cjs", "main.js",
                "index", "--cwd", root.toString(), "--output", output.toString(),
                "--project-name", "shop-backend", "--project-version", "_", "--quiet"), plan.command());
        assertEquals(root, plan.workingDirectory());
        assertEquals(output, plan.generatedArtifact());
        assertTrue(plan.environment().isEmpty(), "the plan adds no environment of its own");
        assertEquals(Duration.ofMinutes(30), plan.timeout());
        assertTrue(Files.isDirectory(run), "the run directory is prepared before the provider starts");
    }

    @Test
    void anExecutablePathIsLaunchedDirectlyAsAnAbsoluteNormalizedCommand() throws Exception {
        Path project = pythonProject("app.py");
        Path executable = temporaryDirectory.resolve("bin").resolve(".").resolve("scip-python");

        IndexerProcessPlan plan = new ScipPythonProcessPlanFactory(executable)
                .create(request(project, IndexingMode.FULL, List.of()), temporaryDirectory.resolve("run"));

        assertEquals(executable.toAbsolutePath().normalize().toString(), plan.command().get(0));
        assertEquals("index", plan.command().get(1));
    }

    @Test
    void aPythonSourceIsRecognizedWhateverTheCaseOfItsExtension() throws Exception {
        Path project = pythonProject("LEGACY.PY");

        IndexerProcessPlan plan = new ScipPythonProcessPlanFactory(List.of("scip-python"))
                .create(request(project, IndexingMode.FULL, List.of()), temporaryDirectory.resolve("run"));

        assertEquals("index", plan.command().get(1));
    }

    @Test
    void aProjectWithoutAnyPythonSourceIsRefusedAndNoRunDirectoryIsCreated() throws Exception {
        Path project = pythonProject("README.md");
        Path run = temporaryDirectory.resolve("run");
        ScipPythonProcessPlanFactory factory = new ScipPythonProcessPlanFactory(List.of("scip-python"));
        IndexingExecutionRequest request = request(project, IndexingMode.FULL, List.of());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> factory.create(request, run));

        assertTrue(refusal.getMessage().contains("requires at least one .py source"), refusal.getMessage());
        assertFalse(Files.exists(run), "a refused plan must not prepare the run directory");
    }

    @Test
    void anIncrementalRunIsRefusedBecauseItIsNotQualified() throws Exception {
        Path project = pythonProject("app.py");
        Path run = temporaryDirectory.resolve("run");
        ScipPythonProcessPlanFactory factory = new ScipPythonProcessPlanFactory(List.of("scip-python"));
        IndexingExecutionRequest request = request(project, IndexingMode.INCREMENTAL, List.of("app.py"));

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> factory.create(request, run));

        assertTrue(refusal.getMessage().contains("incremental execution is not qualified"), refusal.getMessage());
        assertFalse(Files.exists(run));
    }

    @Test
    void theLauncherMustBeAFullyNamedCommand() {
        assertThrows(NullPointerException.class, () -> new ScipPythonProcessPlanFactory((List<String>) null));
        assertThrows(NullPointerException.class, () -> new ScipPythonProcessPlanFactory((Path) null));
        List<String> empty = List.of();
        List<String> blankElement = List.of("node", " ");
        assertThrows(IllegalArgumentException.class, () -> new ScipPythonProcessPlanFactory(empty));
        assertThrows(IllegalArgumentException.class, () -> new ScipPythonProcessPlanFactory(blankElement));
        assertThrows(IllegalArgumentException.class,
                () -> new ScipPythonProcessPlanFactory(Arrays.asList("node", null)));
    }

    @Test
    void theLauncherIsCopiedSoLaterChangesCannotRedirectTheCommand() throws Exception {
        Path project = pythonProject("app.py");
        List<String> launcher = new java.util.ArrayList<>(List.of("scip-python"));
        ScipPythonProcessPlanFactory factory = new ScipPythonProcessPlanFactory(launcher);
        launcher.set(0, "malicious");

        IndexerProcessPlan plan = factory.create(request(project, IndexingMode.FULL, List.of()),
                temporaryDirectory.resolve("run"));

        assertEquals("scip-python", plan.command().get(0));
    }

    private static IndexingExecutionRequest request(Path project, IndexingMode mode, List<String> changedFiles) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-python", "0.6.6", "scip-python", Set.of(Language.PYTHON), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.PYTHON, descriptor), mode, changedFiles);
    }
}
