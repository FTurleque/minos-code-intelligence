package com.minos.cli;

import com.minos.domain.Symbol;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableCliIntegrationTest {

    @Test
    void projectIndexArchitectureAndImpactAreScriptableEndToEnd(@TempDir Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));

        CommandResult add = run(home,
                "project", "add", fixture.toString(), "--name", "m9-typescript", "--format", "json");
        assertEquals(0, add.exitCode(), add.error());
        assertTrue(add.output().contains("\"name\":\"m9-typescript\""), add.output());
        assertTrue(add.output().contains("\"indexState\":\"NEVER_INDEXED\""), add.output());

        CommandResult list = run(home, "project", "list", "--format", "json");
        assertEquals(0, list.exitCode(), list.error());
        assertTrue(list.output().contains("\"count\":1"), list.output());

        CommandResult index = run(home,
                "index", "m9-typescript",
                "--scip", scip.toString(),
                "--provider", "scip-typescript",
                "--provider-version", "0.4.0",
                "--format", "json");
        assertEquals(0, index.exitCode(), index.error());
        assertTrue(index.output().contains("\"providerId\":\"scip-typescript\""), index.output());
        assertTrue(index.output().contains("\"normalizedSymbolCount\":"), index.output());

        CommandResult status = run(home, "index-status", "m9-typescript", "--format", "json");
        assertEquals(0, status.exitCode(), status.error());
        assertTrue(status.output().contains("\"state\":\"READY\""), status.output());
        assertTrue(status.output().contains("\"lastSuccessfulIndexAt\":"), status.output());

        CommandResult inspect = run(home, "inspect", "m9-typescript", "--format", "json");
        assertEquals(0, inspect.exitCode(), inspect.error());
        assertTrue(inspect.output().contains("\"languages\":[\"TYPESCRIPT\"]"), inspect.output());
        assertTrue(inspect.output().contains("\"buildSystems\":[\"NPM\"]"), inspect.output());

        CommandResult architecture = run(home, "architecture", "m9-typescript", "--format", "json");
        assertEquals(0, architecture.exitCode(), architecture.error());
        assertTrue(architecture.output().contains("\"moduleCount\":3"), architecture.output());
        assertTrue(architecture.output().contains("\"technologies\":[\"TYPESCRIPT\",\"NPM\"]"),
                architecture.output());

        RegisteredProject project = new LocalProjectRegistry(home.resolve("registry"))
                .listProjects().getFirst();
        CodeKnowledgeSnapshot snapshot = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
                .loadActiveKnowledge(project.id()).orElseThrow();
        Symbol greetingPort = snapshot.symbols().stream()
                .filter(symbol -> "GreetingPort".equals(symbol.qualifiedName()))
                .findFirst()
                .orElseThrow();

        CommandResult impact = run(home,
                "impact", "m9-typescript", greetingPort.id(), "--format", "json");
        assertEquals(0, impact.exitCode(), impact.error());
        assertTrue(impact.output().contains("\"impactCount\":2"), impact.output());
        assertTrue(impact.output().contains("\"testCount\":1"), impact.output());

        System.out.printf(
                "M9 stable CLI: project=%s, snapshot=%s, architecture-modules=3, impact-root=GreetingPort%n",
                project.id(), snapshot.snapshotId());
    }

    @Test
    void indexRequiresAnExplicitScipArtifact(@TempDir Path home) throws Exception {
        CommandResult result = run(home, "index", "missing-project", "--provider", "scip-typescript");

        assertEquals(FindSymbolCommand.USAGE_ERROR, result.exitCode());
        assertTrue(result.error().contains("--scip is required"), result.error());
        assertEquals("", result.output());
    }

    private static CommandResult run(Path home, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exitCode = MinosLauncher.run(home, arguments, output, error);
        return new CommandResult(exitCode, output.toString(), error.toString());
    }

    private record CommandResult(int exitCode, String output, String error) {
    }
}
