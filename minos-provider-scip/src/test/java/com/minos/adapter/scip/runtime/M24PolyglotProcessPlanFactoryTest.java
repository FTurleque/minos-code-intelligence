package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M24PolyglotProcessPlanFactoryTest {

    @Test
    void clangRequiresCompilationDatabaseAndPinsGeneratedArtifact(@TempDir Path temp) throws Exception {
        Path executable = Files.createFile(temp.resolve("scip-clang"));
        Path project = Files.createDirectories(temp.resolve("clang-project"));
        Path run = temp.resolve("clang-run");
        Path compdb = Files.writeString(project.resolve("compile_commands.json"), "[]");
        var plan = new ScipClangProcessPlanFactory(executable).create(request(project), run);
        Path output = run.resolve("index.scip").toAbsolutePath().normalize();
        assertEquals(output, plan.generatedArtifact());
        assertFalse(plan.generatedArtifact().startsWith(project));
        assertTrue(plan.command().stream().anyMatch(value -> value.equals("--compdb-path=" + compdb.toAbsolutePath().normalize())));
        assertTrue(plan.command().contains("--index-output-path=" + output));
    }

    @Test
    void dotnetRequiresProjectMarkerAndUsesExternalOutput(@TempDir Path temp) throws Exception {
        Path executable = Files.createFile(temp.resolve("scip-dotnet"));
        Path project = Files.createDirectories(temp.resolve("dotnet-project"));
        Path run = temp.resolve("dotnet-run");
        Files.writeString(project.resolve("fixture.csproj"), "<Project />");
        var plan = new ScipDotnetProcessPlanFactory(executable).create(request(project), run);
        Path output = run.resolve("index.scip").toAbsolutePath().normalize();
        assertEquals(output, plan.generatedArtifact());
        assertFalse(plan.generatedArtifact().startsWith(project));
        assertTrue(plan.command().contains("index"));
        assertTrue(plan.command().contains("--output"));
        assertTrue(plan.command().contains(output.toString()));
    }

    @Test
    void goQualificationRejectsGoWorkWithoutCanonicalGoMod(@TempDir Path temp) throws Exception {
        Path executable = Files.createFile(temp.resolve("scip-go"));
        Path project = Files.createDirectories(temp.resolve("go-project"));
        Path run = temp.resolve("go-run");
        Files.writeString(project.resolve("go.work"), "go 1.22");
        ScipGoProcessPlanFactory factory = new ScipGoProcessPlanFactory(executable);
        assertThrows(IllegalArgumentException.class, () -> factory.create(request(project), run));
        Files.writeString(project.resolve("go.mod"), "module example.com/test\ngo 1.22\n");
        var plan = factory.create(request(project), run);
        Path output = run.resolve("index.scip").toAbsolutePath().normalize();
        assertEquals(output, plan.generatedArtifact());
        assertFalse(plan.generatedArtifact().startsWith(project));
        assertTrue(plan.command().contains("--output"));
        assertTrue(plan.command().contains(output.toString()));
    }

    @Test
    void rustUsesNativeScipSubcommandAndRequiresCargoToml(@TempDir Path temp) throws Exception {
        Path executable = Files.createFile(temp.resolve("rust-analyzer"));
        Path project = Files.createDirectories(temp.resolve("rust-project"));
        Path run = temp.resolve("rust-run");
        RustAnalyzerScipProcessPlanFactory factory = new RustAnalyzerScipProcessPlanFactory(executable);
        assertThrows(IllegalArgumentException.class, () -> factory.create(request(project), run));
        Files.writeString(project.resolve("Cargo.toml"), "[package]\nname='fixture'\nversion='0.1.0'\n");
        var plan = factory.create(request(project), run);
        Path output = run.resolve("index.scip").toAbsolutePath().normalize();
        assertEquals(output, plan.generatedArtifact());
        assertFalse(plan.generatedArtifact().startsWith(project));
        assertTrue(plan.command().contains("scip"));
        assertTrue(plan.command().contains("--output"));
        assertTrue(plan.command().contains(output.toString()));
        assertEquals(run.resolve("cargo-target").toAbsolutePath().normalize().toString(),
                plan.environment().get("CARGO_TARGET_DIR"));
    }

    @Test
    void everyNewProcessPlanRejectsIncrementalMode(@TempDir Path temp) throws Exception {
        Path executable = Files.createFile(temp.resolve("provider"));
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("compile_commands.json"), "[]");
        Files.writeString(project.resolve("fixture.csproj"), "<Project />");
        Files.writeString(project.resolve("go.mod"), "module example.com/test\ngo 1.22\n");
        Files.writeString(project.resolve("Cargo.toml"), "[package]\nname='fixture'\nversion='0.1.0'\n");
        IndexingExecutionRequest incremental = request(project, IndexingMode.INCREMENTAL, List.of("src/main.txt"));
        assertThrows(IllegalStateException.class,
                () -> new ScipClangProcessPlanFactory(executable).create(incremental, temp.resolve("run")));
        assertThrows(IllegalStateException.class,
                () -> new ScipDotnetProcessPlanFactory(executable).create(incremental, temp.resolve("run")));
        assertThrows(IllegalStateException.class,
                () -> new ScipGoProcessPlanFactory(executable).create(incremental, temp.resolve("run")));
        assertThrows(IllegalStateException.class,
                () -> new RustAnalyzerScipProcessPlanFactory(executable).create(incremental, temp.resolve("run")));
    }

    private static IndexingExecutionRequest request(Path project) {
        return request(project, IndexingMode.FULL, List.of());
    }

    private static IndexingExecutionRequest request(Path project, IndexingMode mode, List<String> changedFiles) {
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                project,
                new IndexerSelection(Language.GO, ScipIndexerCatalog.scipGo()),
                mode,
                changedFiles);
    }
}
