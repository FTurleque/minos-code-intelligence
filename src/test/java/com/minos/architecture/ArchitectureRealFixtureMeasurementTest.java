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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        ArchitectureModule rootModule = module(overview, "");
        ArchitectureModule api = module(overview, "packages/api");
        ArchitectureModule app = module(overview, "packages/app");

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
}
