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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureJavaFixtureMeasurementTest {

    @Test
    void measuresVersionedSingleModuleJavaIndexWithoutInventingInterModuleCentrality(@TempDir Path root)
            throws Exception {
        Path fixtureRoot = Path.of("fixtures", "java", "java-simple");
        Path indexFile = fixtureRoot.resolve(Path.of(".minos-m0", "scip-java", "index.scip"));
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        projectId,
                        "snapshot-m6-java-simple",
                        null,
                        "scip-java",
                        "0.13.1",
                        "m6.4-java-fixture",
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

        assertEquals(1, overview.moduleCount());
        assertEquals(0, graph.interModuleDependencyCount());
        assertEquals(0, graph.moduleEdgeCount());
        assertEquals(0, concentration.interModuleDependencyCount());
        assertEquals(0.0, concentration.incomingHerfindahlIndex(), 0.0);
        assertEquals(0.0, concentration.outgoingHerfindahlIndex(), 0.0);
        assertEquals(0.0, concentration.maxIncomingShare(), 0.0);
        assertEquals(0.0, concentration.maxOutgoingShare(), 0.0);

        System.out.printf(
                "M6.4 java-simple: modules=%d, dependsOn=%d, inter=%d, intra=%d, unassigned=%d, "
                        + "edges=%d, HHI-in=%.6f, HHI-out=%.6f%n",
                overview.moduleCount(),
                graph.totalDependencyCount(),
                graph.interModuleDependencyCount(),
                graph.intraModuleDependencyCount(),
                graph.unassignedDependencyCount(),
                graph.moduleEdgeCount(),
                concentration.incomingHerfindahlIndex(),
                concentration.outgoingHerfindahlIndex()
        );
    }
}
