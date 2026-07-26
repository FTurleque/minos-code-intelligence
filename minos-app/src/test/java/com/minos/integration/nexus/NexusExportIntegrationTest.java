package com.minos.integration.nexus;

import com.minos.api.LocalMinosApi;
import com.minos.api.MinosApi;
import com.minos.cli.MinosLauncher;
import com.minos.registry.LocalProjectRegistry;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusExportIntegrationTest {

    @Test
    void exportsActiveMinosKnowledgeForNexusAndCliJson(@TempDir Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        MinosApi api = new LocalMinosApi(home);
        MinosApi.ProjectDto project = api.addProject(fixture, "m13-typescript");
        MinosApi.IndexImportDto imported = api.importScip(
                project.id(),
                scip,
                new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, null));

        NexusExportService service = new NexusExportService(
                new LocalProjectRegistry(home.resolve("registry")),
                new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")));
        NexusExportContract.ExportSnapshot snapshot = service.export(fixture);

        assertEquals("1", snapshot.contractVersion());
        assertEquals("MINOS", snapshot.producer());
        assertEquals(project.id(), snapshot.project().id());
        assertEquals(imported.snapshotId(), snapshot.project().snapshotId());
        assertFalse(snapshot.symbols().isEmpty());
        assertFalse(snapshot.relations().isEmpty());

        NexusExportContract.ExportSymbol greetingPort = snapshot.symbols().stream()
                .filter(symbol -> "GreetingPort".equals(symbol.qualifiedName()))
                .findFirst()
                .orElseThrow();
        assertEquals("typescript", greetingPort.language());
        assertFalse(Path.of(greetingPort.filePath()).isAbsolute());
        assertFalse(greetingPort.filePath().contains(".."));
        assertEquals("scip-typescript", greetingPort.origin().providerId());

        assertTrue(snapshot.relations().stream().anyMatch(relation ->
                "IMPLEMENTS".equals(relation.kind())
                        && "RESOLVED".equals(relation.resolutionStatus())
                        && relation.sourceQualifiedName() != null
                        && relation.targetQualifiedName() != null));

        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exitCode = MinosLauncher.run(
                home,
                new String[]{"nexus-export", "--root", fixture.toString()},
                output,
                error);
        assertEquals(0, exitCode);
        assertEquals("", error.toString());
        assertTrue(output.toString().startsWith("{\"contractVersion\":\"1\",\"producer\":\"MINOS\""));
        assertTrue(output.toString().contains("\"snapshotId\":\"" + imported.snapshotId() + "\""));
        assertTrue(output.toString().contains("\"qualifiedName\":\"GreetingPort\""));

        System.out.printf(
                "M13 MINOS export: contract=%s, project=%s, snapshot=%s, symbols=%d, relations=%d%n",
                snapshot.contractVersion(), snapshot.project().id(), snapshot.project().snapshotId(),
                snapshot.symbols().size(), snapshot.relations().size());
    }

    @Test
    void cliFailsCleanlyForUnregisteredProject(@TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("unregistered"));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = MinosLauncher.run(
                home,
                new String[]{"nexus-export", "--root", project.toString()},
                output,
                error);

        assertEquals(1, exitCode);
        assertEquals("", output.toString());
        assertTrue(error.toString().contains("not registered in MINOS"));
    }
}
