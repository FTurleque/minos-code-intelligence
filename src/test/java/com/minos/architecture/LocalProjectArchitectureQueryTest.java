package com.minos.architecture;

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
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalProjectArchitectureQueryTest {

    @Test
    void reloadsRegisteredProjectDiscoveryAndActiveSnapshot(@TempDir Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path sourceDirectory = Files.createDirectories(
                projectRoot.resolve("src/main/java/com/acme"));
        Files.writeString(
                sourceDirectory.resolve("App.java"),
                "package com.acme; public final class App {}"
        );

        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "architecture-fixture");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(
                project.id(),
                "snapshot-m6-local",
                List.of(symbol(project, "app", "src/main/java/com/acme/App.java")),
                List.of(),
                List.of()
        );

        LocalProjectArchitectureQuery query = new LocalProjectArchitectureQuery(registry, snapshots);
        ArchitectureOverview overview = query.getArchitectureOverview("architecture-fixture");
        ArchitectureTechnologyReport technologies = query.getArchitectureTechnologies("architecture-fixture");
        ArchitectureIntelligenceView intelligence = query.getArchitectureIntelligence("architecture-fixture");
        ArchitectureModuleContext rootContext = query.getModuleContext("architecture-fixture", ".");

        assertEquals(project.id().toString(), overview.projectId());
        assertEquals("snapshot-m6-local", overview.snapshotId());
        assertEquals(List.of("JAVA"), overview.languages());
        assertEquals(List.of("MAVEN"), overview.buildSystems());
        assertEquals(1, overview.localSymbolCount());
        assertEquals(0, overview.unassignedLocalSymbolCount());
        assertEquals(1, overview.moduleCount());
        assertEquals("com.acme", overview.modules().getFirst().namespaces().getFirst().name());

        assertEquals(project.id().toString(), technologies.projectId());
        assertEquals("snapshot-m6-local", technologies.snapshotId());
        assertEquals(List.of("JAVA", "MAVEN"), technologies.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());
        assertEquals(List.of(overview.modules().getFirst().id()), technology(technologies, "JAVA").moduleIds());
        assertEquals(List.of(overview.modules().getFirst().id()), technology(technologies, "MAVEN").moduleIds());

        assertEquals(project.id().toString(), intelligence.projectId());
        assertEquals("snapshot-m6-local", intelligence.snapshotId());
        assertEquals(1, intelligence.overview().moduleCount());
        assertEquals(0, intelligence.dependencies().totalDependencyCount());
        assertEquals(List.of("JAVA", "MAVEN"), intelligence.technologies().technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());

        assertEquals(overview.modules().getFirst().id(), rootContext.module().id());
        assertEquals(0, rootContext.incomingModuleEdgeCount());
        assertEquals(0, rootContext.outgoingModuleEdgeCount());
        assertEquals(0, rootContext.centrality().incomingRank());
        assertEquals(0, rootContext.centrality().outgoingRank());
        assertEquals(List.of("JAVA", "MAVEN"), rootContext.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());
    }

    @Test
    void reloadsPersistedDependenciesAndRanksDiscoveredModules(@TempDir Path root)
            throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("multi-module"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path apiRoot = Files.createDirectories(projectRoot.resolve("api/src/main/java/com/acme/api"));
        Path appRoot = Files.createDirectories(projectRoot.resolve("app/src/main/java/com/acme/app"));
        Files.writeString(projectRoot.resolve("api/pom.xml"), "<project/>");
        Files.writeString(projectRoot.resolve("app/pom.xml"), "<project/>");
        Files.writeString(apiRoot.resolve("Api.java"), "package com.acme.api; public class Api {}");
        Files.writeString(appRoot.resolve("App.java"), "package com.acme.app; public class App {}");

        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "dependency-fixture");
        Symbol api = symbol(project, "api", "api/src/main/java/com/acme/api/Api.java");
        Symbol app = symbol(project, "app", "app/src/main/java/com/acme/app/App.java");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(
                project.id(),
                "snapshot-m6-dependencies",
                List.of(api, app),
                List.of(),
                List.of(dependency(project, app, api))
        );

        LocalProjectArchitectureQuery query = new LocalProjectArchitectureQuery(registry, snapshots);
        ArchitectureOverview overview = query.getArchitectureOverview("dependency-fixture");
        ArchitectureDependencyGraph graph = query.getModuleDependencies("dependency-fixture");
        ArchitectureConcentrationReport concentration = query.getArchitectureConcentration("dependency-fixture");
        ArchitectureCentralityReport centrality = query.getArchitectureCentrality("dependency-fixture");
        ArchitectureTechnologyReport technologies = query.getArchitectureTechnologies("dependency-fixture");
        ArchitectureIntelligenceView intelligence = query.getArchitectureIntelligence("dependency-fixture");

        ArchitectureModule apiModule = module(overview, "api");
        ArchitectureModule appModule = module(overview, "app");
        ArchitectureModule rootModule = module(overview, "");
        ArchitectureModuleContext apiContext = query.getModuleContext("dependency-fixture", "api");
        ArchitectureModuleContext appContext = query.getModuleContext("dependency-fixture", appModule.id());
        ArchitectureModuleContext rootContext = query.getModuleContext("dependency-fixture", ".");

        assertEquals(1, graph.totalDependencyCount());
        assertEquals(1, graph.interModuleDependencyCount());
        assertEquals(0, graph.intraModuleDependencyCount());
        assertEquals(0, graph.unassignedDependencyCount());
        assertEquals(1, graph.moduleEdgeCount());
        assertEquals(appModule.id(), graph.dependencies().getFirst().sourceModuleId());
        assertEquals(apiModule.id(), graph.dependencies().getFirst().targetModuleId());

        assertEquals(3, concentration.moduleCount());
        assertEquals(1, concentration.interModuleDependencyCount());
        assertEquals(1.0, concentration.incomingHerfindahlIndex(), 0.0);
        assertEquals(1.0, concentration.outgoingHerfindahlIndex(), 0.0);
        assertEquals(1.0, concentration.maxIncomingShare(), 0.0);
        assertEquals(1.0, concentration.maxOutgoingShare(), 0.0);
        assertEquals(1, metric(concentration, apiModule.id()).incomingDependencyCount());
        assertEquals(0, metric(concentration, apiModule.id()).outgoingDependencyCount());
        assertEquals(0, metric(concentration, appModule.id()).incomingDependencyCount());
        assertEquals(1, metric(concentration, appModule.id()).outgoingDependencyCount());

        assertEquals(3, centrality.moduleCount());
        assertEquals(List.of(apiModule.id()), centrality.topIncomingModuleIds());
        assertEquals(List.of(appModule.id()), centrality.topOutgoingModuleIds());
        assertEquals(1, centrality(centrality, apiModule.id()).incomingRank());
        assertEquals(0, centrality(centrality, apiModule.id()).outgoingRank());
        assertEquals(0, centrality(centrality, appModule.id()).incomingRank());
        assertEquals(1, centrality(centrality, appModule.id()).outgoingRank());

        assertEquals(List.of("JAVA", "MAVEN"), technologies.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());
        assertEquals(Set.of(apiModule.id(), appModule.id()), Set.copyOf(technology(technologies, "JAVA").moduleIds()));
        assertEquals(3, technology(technologies, "MAVEN").moduleIds().size());

        assertEquals(project.id().toString(), intelligence.projectId());
        assertEquals("snapshot-m6-dependencies", intelligence.snapshotId());
        assertEquals(overview.moduleCount(), intelligence.overview().moduleCount());
        assertEquals(graph.totalDependencyCount(), intelligence.dependencies().totalDependencyCount());
        assertEquals(centrality.topIncomingModuleIds(), intelligence.centrality().topIncomingModuleIds());
        assertEquals(technologies.technologies(), intelligence.technologies().technologies());

        assertEquals(apiModule.id(), apiContext.module().id());
        assertEquals(1, apiContext.incomingModuleEdgeCount());
        assertEquals(0, apiContext.outgoingModuleEdgeCount());
        assertEquals(1, apiContext.concentration().incomingDependencyCount());
        assertEquals(1, apiContext.centrality().incomingRank());
        assertEquals(List.of("JAVA", "MAVEN"), apiContext.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());

        assertEquals(appModule.id(), appContext.module().id());
        assertEquals(0, appContext.incomingModuleEdgeCount());
        assertEquals(1, appContext.outgoingModuleEdgeCount());
        assertEquals(1, appContext.concentration().outgoingDependencyCount());
        assertEquals(1, appContext.centrality().outgoingRank());

        assertEquals(rootModule.id(), rootContext.module().id());
        assertEquals(0, rootContext.incomingModuleEdgeCount());
        assertEquals(0, rootContext.outgoingModuleEdgeCount());
        assertEquals(List.of("MAVEN"), rootContext.technologies().stream()
                .map(ArchitectureTechnology::name)
                .toList());

        assertThrows(IllegalArgumentException.class,
                () -> query.getModuleContext("dependency-fixture", "missing-module"));
    }

    private static ArchitectureModule module(ArchitectureOverview overview, String relativePath) {
        return overview.modules().stream()
                .filter(module -> relativePath.equals(module.relativePath()))
                .findFirst()
                .orElseThrow();
    }

    private static ArchitectureModuleConcentration metric(
            ArchitectureConcentrationReport report,
            String moduleId
    ) {
        return report.modules().stream()
                .filter(metric -> moduleId.equals(metric.moduleId()))
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

    private static Symbol symbol(RegisteredProject project, String id, String fileId) {
        return new Symbol(
                "sym:" + id,
                "key:" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                "main",
                fileId,
                null,
                SymbolKind.CLASS,
                id,
                "com.acme." + id,
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("test", "TEST", "1", "run", OriginType.OTHER),
                false,
                false,
                Set.of()
        );
    }

    private static Relationship dependency(RegisteredProject project, Symbol source, Symbol target) {
        CodeEntityRef sourceRef = new CodeEntityRef(CodeEntityType.SYMBOL, source.id());
        CodeEntityRef targetRef = new CodeEntityRef(CodeEntityType.SYMBOL, target.id());
        return new Relationship(
                "rel:dependency",
                project.id().toString(),
                sourceRef,
                targetRef,
                null,
                RelationshipKind.DEPENDS_ON,
                null,
                ResolutionStatus.RESOLVED,
                InformationNature.DERIVED,
                1.0,
                new Origin("minos", "RELATIONSHIP_DERIVATION", "M3", "run", OriginType.DERIVED_BY_MINOS),
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Direct persisted dependency",
                        sourceRef,
                        targetRef,
                        null,
                        1.0
                ))
        );
    }
}