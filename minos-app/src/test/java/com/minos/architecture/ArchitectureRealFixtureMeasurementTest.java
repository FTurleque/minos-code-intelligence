package com.minos.architecture;

import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureRealFixtureMeasurementTest {

    @Test
    void measuresVersionedTypeScriptMultiModuleIndex(@TempDir Path root) throws Exception {
        Path fixtureRoot = Path.of("fixtures", "typescript", "typescript-modules");
        Path indexFile = fixtureRoot.resolve(
                Path.of(".minos-m0", "scip-typescript", "index.scip"));
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        projectId,
                        "snapshot-m6-real-typescript-modules",
                        null,
                        "scip-typescript",
                        "0.4.0",
                        "m6-real-fixture",
                        Map.of()
                ),
                snapshots
        );

        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(projectId).orElseThrow();
        ProjectDiscovery discovery = new ProjectDiscoveryService().discover(fixtureRoot);
        ArchitectureOverview overview = new ArchitectureTopologyService().build(discovery, snapshot);
        ArchitectureDependencyGraph graph = new ArchitectureDependencyService().build(discovery, snapshot);
        ArchitectureConcentrationReport concentration = new ArchitectureConcentrationService()
                .analyze(overview, graph);
        ArchitectureCentralityReport centrality = new ArchitectureCentralityService().rank(concentration);
        ArchitectureTechnologyReport technologies = new ArchitectureTechnologyService().detect(discovery, overview);
        ArchitectureIntelligenceService intelligenceService = new ArchitectureIntelligenceService();
        ArchitectureIntelligenceView intelligence = intelligenceService.compose(
                overview,
                graph,
                concentration,
                centrality,
                technologies
        );

        ArchitectureModule rootModule = module(overview, "");
        ArchitectureModule api = module(overview, "packages/api");
        ArchitectureModule app = module(overview, "packages/app");
        ArchitectureModuleContext apiContext = intelligenceService.moduleContext(intelligence, "packages/api");
        ArchitectureModuleContext apiByNameContext = intelligenceService.moduleContext(intelligence, "api");
        ArchitectureModuleContext appContext = intelligenceService.moduleContext(intelligence, app.id());
        ArchitectureModuleContext rootContext = intelligenceService.moduleContext(intelligence, ".");

        assertEquals(3, overview.moduleCount());
        assertEquals(4, graph.totalDependencyCount());
        assertTrue(graph.interModuleDependencyCount() > 0);
        assertTrue(graph.moduleEdgeCount() > 0);
        assertTrue(graph.dependencies().stream().anyMatch(edge ->
                app.id().equals(edge.sourceModuleId()) && api.id().equals(edge.targetModuleId())));
        assertEquals(graph.interModuleDependencyCount(), concentration.interModuleDependencyCount());
        assertTrue(concentration.maxIncomingShare() > 0.0);
        assertTrue(concentration.maxOutgoingShare() > 0.0);
        assertTrue(concentration.incomingHerfindahlIndex() > 0.0);
        assertTrue(concentration.outgoingHerfindahlIndex() > 0.0);

        assertEquals(List.of(api.id()), centrality.topIncomingModuleIds());
        assertEquals(List.of(app.id()), centrality.topOutgoingModuleIds());
        assertEquals(1, centrality(centrality, api.id()).incomingRank());
        assertEquals(0, centrality(centrality, api.id()).outgoingRank());
        assertEquals(0, centrality(centrality, app.id()).incomingRank());
        assertEquals(1, centrality(centrality, app.id()).outgoingRank());
        assertEquals(0, centrality(centrality, rootModule.id()).incomingRank());
        assertEquals(0, centrality(centrality, rootModule.id()).outgoingRank());

        assertEquals(List.of("TYPESCRIPT", "NPM"), technologies.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());
        assertEquals(Set.of(api.id(), app.id()), Set.copyOf(technology(technologies, "TYPESCRIPT").moduleIds()));
        assertEquals(
                Set.of(rootModule.id(), api.id(), app.id()),
                Set.copyOf(technology(technologies, "NPM").moduleIds())
        );

        assertEquals(projectId.toString(), intelligence.projectId());
        assertEquals("snapshot-m6-real-typescript-modules", intelligence.snapshotId());
        assertEquals(3, intelligence.overview().moduleCount());
        assertEquals(4, intelligence.dependencies().totalDependencyCount());
        assertEquals(List.of(api.id()), intelligence.centrality().topIncomingModuleIds());
        assertEquals(List.of(app.id()), intelligence.centrality().topOutgoingModuleIds());

        assertEquals(api.id(), apiContext.module().id());
        assertEquals(api.id(), apiByNameContext.module().id());
        assertEquals(1, apiContext.incomingModuleEdgeCount());
        assertEquals(0, apiContext.outgoingModuleEdgeCount());
        assertEquals(4, apiContext.concentration().incomingDependencyCount());
        assertEquals(1, apiContext.centrality().incomingRank());
        assertEquals(List.of("TYPESCRIPT", "NPM"), apiContext.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());

        assertEquals(app.id(), appContext.module().id());
        assertEquals(0, appContext.incomingModuleEdgeCount());
        assertEquals(1, appContext.outgoingModuleEdgeCount());
        assertEquals(4, appContext.concentration().outgoingDependencyCount());
        assertEquals(1, appContext.centrality().outgoingRank());
        assertEquals(List.of("TYPESCRIPT", "NPM"), appContext.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());

        assertEquals(rootModule.id(), rootContext.module().id());
        assertEquals(0, rootContext.incomingModuleEdgeCount());
        assertEquals(0, rootContext.outgoingModuleEdgeCount());
        assertEquals(List.of("NPM"), rootContext.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());

        ArchitectureTechnologyReport wrongSnapshotTechnologies = new ArchitectureTechnologyReport(
                technologies.projectId(),
                "other-snapshot",
                technologies.technologyCount(),
                technologies.technologies(),
                technologies.nature(),
                technologies.evidence()
        );
        assertThrows(IllegalArgumentException.class, () -> intelligenceService.compose(
                overview,
                graph,
                concentration,
                centrality,
                wrongSnapshotTechnologies
        ));

        System.out.printf(
                "M6.3 typescript-modules: modules=%d, dependsOn=%d, inter=%d, intra=%d, unassigned=%d, "
                        + "edges=%d, HHI-in=%.6f, HHI-out=%.6f, max-in=%.6f, max-out=%.6f%n",
                overview.moduleCount(),
                graph.totalDependencyCount(),
                graph.interModuleDependencyCount(),
                graph.intraModuleDependencyCount(),
                graph.unassignedDependencyCount(),
                graph.moduleEdgeCount(),
                concentration.incomingHerfindahlIndex(),
                concentration.outgoingHerfindahlIndex(),
                concentration.maxIncomingShare(),
                concentration.maxOutgoingShare()
        );
        System.out.printf(
                "M6.5 typescript-modules centrality: top-in=%s, top-out=%s, root-in-rank=%d, root-out-rank=%d%n",
                centrality.topIncomingModuleIds(),
                centrality.topOutgoingModuleIds(),
                centrality(centrality, rootModule.id()).incomingRank(),
                centrality(centrality, rootModule.id()).outgoingRank()
        );
        System.out.printf(
                "M6.6 typescript-modules technologies: names=%s, typescript-modules=%s, npm-modules=%s%n",
                technologies.technologies().stream().map(ArchitectureTechnology::name).toList(),
                technology(technologies, "TYPESCRIPT").moduleIds(),
                technology(technologies, "NPM").moduleIds()
        );
        System.out.printf(
                "M6.7 typescript-modules architecture: modules=%d, api-in-edges=%d, api-in-rank=%d, "
                        + "app-out-edges=%d, app-out-rank=%d, root-technologies=%s%n",
                intelligence.overview().moduleCount(),
                apiContext.incomingModuleEdgeCount(),
                apiContext.centrality().incomingRank(),
                appContext.outgoingModuleEdgeCount(),
                appContext.centrality().outgoingRank(),
                rootContext.technologies().stream().map(ArchitectureTechnology::name).toList()
        );
    }

    private static ArchitectureModule module(ArchitectureOverview overview, String relativePath) {
        return overview.modules().stream()
                .filter(module -> relativePath.equals(module.relativePath()))
                .findFirst()
                .orElseThrow();
    }

    private static ArchitectureModuleCentrality centrality(
            ArchitectureCentralityReport report,
            String moduleId
    ) {
        return report.modules().stream()
                .filter(module -> moduleId.equals(module.moduleId()))
                .findFirst()
                .orElseThrow();
    }

    private static ArchitectureTechnology technology(ArchitectureTechnologyReport report, String name) {
        return report.technologies().stream()
                .filter(technology -> name.equals(technology.name()))
                .findFirst()
                .orElseThrow();
    }
}
