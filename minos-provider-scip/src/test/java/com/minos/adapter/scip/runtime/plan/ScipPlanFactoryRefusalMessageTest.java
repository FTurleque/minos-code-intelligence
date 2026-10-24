package com.minos.adapter.scip.runtime.plan;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The SCIP plan factories refuse a project or a missing executable by its leaf name, never by its absolute path,
 * and prepare no run directory when they refuse (AUD-SEC-04).
 */
class ScipPlanFactoryRefusalMessageTest {

    @TempDir
    Path temp;

    @Test
    void aClangProjectWithoutCompilationDatabaseIsRefusedByItsName() throws Exception {
        Path executable = Files.createFile(temp.resolve("scip-clang"));
        Path project = Files.createDirectories(temp.resolve("clang-project"));
        Path run = temp.resolve("run");
        ScipClangProcessPlanFactory factory = new ScipClangProcessPlanFactory(executable);
        IndexingExecutionRequest request = request(project, Language.CPP);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> factory.create(request, run));

        assertEquals("scip-clang requires compile_commands.json at project root or build/compile_commands.json: "
                + "clang-project", refusal.getMessage());
        assertNoAbsolutePath(refusal);
    }

    @Test
    void aDotnetProjectWithoutProjectFileIsRefusedByItsName() throws Exception {
        Path executable = Files.createFile(temp.resolve("scip-dotnet"));
        Path project = Files.createDirectories(temp.resolve("dotnet-project"));
        Path run = temp.resolve("run");
        ScipDotnetProcessPlanFactory factory = new ScipDotnetProcessPlanFactory(executable);
        IndexingExecutionRequest request = request(project, Language.CSHARP);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> factory.create(request, run));

        assertEquals("scip-dotnet requires a .csproj or .sln project: dotnet-project", refusal.getMessage());
        assertNoAbsolutePath(refusal);
        assertFalse(Files.exists(run), "a refused plan prepares no run directory");
    }

    @Test
    void aTypeScriptProjectWithoutManifestIsRefusedByItsName() throws Exception {
        Path executable = Files.createFile(temp.resolve("scip-typescript"));
        Path project = Files.createDirectories(temp.resolve("ts-project"));
        Path run = temp.resolve("run");
        ScipTypeScriptProcessPlanFactory factory = new ScipTypeScriptProcessPlanFactory(executable);
        IndexingExecutionRequest request = request(project, Language.TYPESCRIPT);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> factory.create(request, run));

        assertEquals("scip-typescript requires tsconfig.json or package.json: ts-project", refusal.getMessage());
        assertNoAbsolutePath(refusal);
        assertFalse(Files.exists(run), "a refused plan prepares no run directory");
    }

    @Test
    void aMissingProviderExecutableIsRefusedByItsName() throws Exception {
        Path project = Files.createDirectories(temp.resolve("dotnet-project"));
        Files.writeString(project.resolve("fixture.csproj"), "<Project />");
        Path run = temp.resolve("run");
        ScipDotnetProcessPlanFactory factory = new ScipDotnetProcessPlanFactory(temp.resolve("missing-scip-dotnet"));
        IndexingExecutionRequest request = request(project, Language.CSHARP);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> factory.create(request, run));

        assertEquals("scip-dotnet executable is missing: missing-scip-dotnet", refusal.getMessage());
        assertNoAbsolutePath(refusal);
        assertFalse(Files.exists(run), "a refused plan prepares no run directory");
    }

    private void assertNoAbsolutePath(Exception refusal) {
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    private static IndexingExecutionRequest request(Path project, Language language) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture", "1", "fixture", Set.of(language), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(language, descriptor), IndexingMode.FULL, List.of());
    }
}
