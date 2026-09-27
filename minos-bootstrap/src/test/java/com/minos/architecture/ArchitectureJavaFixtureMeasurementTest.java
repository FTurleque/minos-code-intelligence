package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureJavaFixtureMeasurementTest {

    @Test
    void measuresVersionedJavaMultiModuleTopologyFromControlledGroundTruth(@TempDir Path root)
            throws Exception {
        Path fixtureRoot = Path.of("fixtures", "java", "java-multi-module");
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        Symbol api = symbol(
                projectId,
                "api-greeting-port",
                "api/src/main/java/com/minos/fixture/multimodule/api/GreetingPort.java",
                "com.minos.fixture.multimodule.api.GreetingPort"
        );
        Symbol app = symbol(
                projectId,
                "app-greeting-service",
                "app/src/main/java/com/minos/fixture/multimodule/app/GreetingService.java",
                "com.minos.fixture.multimodule.app.GreetingService"
        );

        snapshots.publish(
                projectId,
                "snapshot-m6-java-multi-module",
                List.of(api, app),
                List.of(),
                List.of(dependency(projectId, app, api))
        );

        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(projectId).orElseThrow();
        ProjectDiscovery discovery = new ProjectDiscoveryService().discover(fixtureRoot);
        ArchitectureOverview overview = new ArchitectureTopologyService().build(discovery, snapshot);
        ArchitectureDependencyGraph graph = new ArchitectureDependencyService().build(discovery, snapshot);
        ArchitectureConcentrationReport concentration = new ArchitectureConcentrationService()
                .analyze(overview, graph);

        ArchitectureModule apiModule = module(overview, "api");
        ArchitectureModule appModule = module(overview, "app");

        assertEquals(3, overview.moduleCount());
        assertEquals(1, graph.totalDependencyCount());
        assertEquals(1, graph.interModuleDependencyCount());
        assertEquals(0, graph.intraModuleDependencyCount());
        assertEquals(0, graph.unassignedDependencyCount());
        assertEquals(1, graph.moduleEdgeCount());
        assertEquals(appModule.id(), graph.dependencies().getFirst().sourceModuleId());
        assertEquals(apiModule.id(), graph.dependencies().getFirst().targetModuleId());
        assertEquals(1.0, concentration.incomingHerfindahlIndex(), 0.0);
        assertEquals(1.0, concentration.outgoingHerfindahlIndex(), 0.0);
        assertEquals(1.0, concentration.maxIncomingShare(), 0.0);
        assertEquals(1.0, concentration.maxOutgoingShare(), 0.0);

        System.out.printf(
                "M6.4 java-multi-module: modules=%d, dependsOn=%d, inter=%d, intra=%d, unassigned=%d, "
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
    }

    private static ArchitectureModule module(ArchitectureOverview overview, String relativePath) {
        return overview.modules().stream()
                .filter(module -> relativePath.equals(module.relativePath()))
                .findFirst()
                .orElseThrow();
    }

    private static Symbol symbol(UUID projectId, String id, String fileId, String qualifiedName) {
        return new Symbol(
                "sym:" + id,
                "key:" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                "main",
                fileId,
                null,
                SymbolKind.CLASS,
                qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                qualifiedName,
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("m6-calibration", "CONTROLLED_GROUND_TRUTH", "1", "m6.4", OriginType.OTHER),
                false,
                false,
                Set.of()
        );
    }

    private static Relationship dependency(UUID projectId, Symbol source, Symbol target) {
        CodeEntityRef sourceRef = new CodeEntityRef(CodeEntityType.SYMBOL, source.id());
        CodeEntityRef targetRef = new CodeEntityRef(CodeEntityType.SYMBOL, target.id());
        return new Relationship(
                "rel:java-multi-module-app-api",
                projectId.toString(),
                sourceRef,
                targetRef,
                null,
                RelationshipKind.DEPENDS_ON,
                null,
                ResolutionStatus.RESOLVED,
                InformationNature.DERIVED,
                1.0,
                new Origin(
                        "m6-calibration",
                        "CONTROLLED_GROUND_TRUTH",
                        "1",
                        "m6.4",
                        OriginType.DERIVED_BY_MINOS
                ),
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Controlled calibration dependency matching java-multi-module expected app -> api relation",
                        sourceRef,
                        targetRef,
                        null,
                        1.0
                ))
        );
    }
}
