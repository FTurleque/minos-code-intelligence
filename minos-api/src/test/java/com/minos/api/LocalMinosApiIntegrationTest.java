package com.minos.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMinosApiIntegrationTest {

    @Test
    void publicApiExposesProjectsIndexSymbolsRelationsArchitectureAndImpact(@TempDir Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        MinosApi api = new LocalMinosApi(home);

        assertEquals("1", api.contractVersion());

        MinosApi.ProjectDto registered = api.addProject(fixture, "m11-typescript");
        assertEquals("NEVER_INDEXED", registered.indexState());
        assertEquals(3, registered.moduleCount());
        assertTrue(registered.languages().contains("TYPESCRIPT"));

        MinosApi.IndexImportDto imported = api.importScip(
                "m11-typescript",
                scip,
                new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, null)
        );
        assertEquals("scip-typescript", imported.providerId());
        assertTrue(imported.normalizedSymbolCount() > 0);
        assertTrue(imported.relationshipCount() > 0);

        MinosApi.ProjectDto indexed = api.getProject("m11-typescript");
        assertEquals("READY", indexed.indexState());
        assertEquals(imported.snapshotId(), indexed.activeSnapshotId());
        assertEquals("scip-typescript", indexed.providerId());
        assertEquals(1, api.listProjects().size());

        MinosApi.SymbolDto greetingPort = api.findSymbols(
                        "m11-typescript",
                        MinosApi.SymbolQuery.lexical("GreetingPort", 20)
                ).stream()
                .filter(symbol -> "GreetingPort".equals(symbol.qualifiedName()))
                .findFirst()
                .orElseThrow();
        assertEquals("GreetingPort", greetingPort.name());
        assertEquals("typescript", greetingPort.language());
        assertNotNull(greetingPort.origin());

        var incoming = api.findRelationships(
                "m11-typescript",
                MinosApi.RelationshipQuery.incomingSymbol(greetingPort.id(), Set.of("IMPLEMENTS"), 50)
        );
        assertFalse(incoming.isEmpty());
        assertTrue(incoming.stream().allMatch(relation -> "IMPLEMENTS".equals(relation.kind())));
        assertTrue(incoming.stream().allMatch(relation -> relation.source() != null));

        MinosApi.ArchitectureDto architecture = api.getArchitecture("m11-typescript");
        assertEquals(3, architecture.moduleCount());
        assertEquals(3, architecture.modules().size());
        assertTrue(architecture.technologies().contains("TYPESCRIPT"));
        assertTrue(architecture.technologies().contains("NPM"));

        MinosApi.ArchitectureGraphDto graph = api.getArchitectureGraph("m11-typescript");
        assertEquals(architecture.projectId(), graph.projectId());
        assertEquals(architecture.snapshotId(), graph.snapshotId());
        assertEquals(3, graph.moduleCount());
        assertTrue(graph.edgeCount() > 0);
        assertTrue(graph.dependencies().stream().anyMatch(edge ->
                "app".equals(edge.sourceModuleName()) && "api".equals(edge.targetModuleName())));
        assertTrue(graph.dependencies().stream().allMatch(edge -> edge.dependencyCount() > 0));

        MinosApi.ModuleContextDto apiModule = api.getModuleContext("m11-typescript", "packages/api");
        assertEquals("packages/api", apiModule.module().relativePath());
        assertTrue(apiModule.incomingDependencyCount() > 0);

        MinosApi.ImpactReportDto impact = api.analyzeImpact(
                "m11-typescript",
                MinosApi.ImpactQuery.defaults(greetingPort.id())
        );
        assertEquals(greetingPort.id(), impact.rootSymbol().id());
        assertEquals(2, impact.impactCount());
        assertEquals(1, impact.testCount());
        assertTrue(impact.limitations().contains("DYNAMIC_DISPATCH_NOT_PROVEN"));

        MinosApi.MinosApiException invalidKind = assertThrows(
                MinosApi.MinosApiException.class,
                () -> api.findSymbols(
                        "m11-typescript",
                        new MinosApi.SymbolQuery("GreetingPort", null, "NOT_A_KIND", null, 20)
                )
        );
        assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, invalidKind.code());

        System.out.printf(
                "M11 public API: version=%s, project=%s, snapshot=%s, modules=%d, graph-edges=%d, impact=%d, tests=%d%n",
                api.contractVersion(), indexed.id(), indexed.activeSnapshotId(), architecture.moduleCount(),
                graph.edgeCount(), impact.impactCount(), impact.testCount()
        );
    }
}
