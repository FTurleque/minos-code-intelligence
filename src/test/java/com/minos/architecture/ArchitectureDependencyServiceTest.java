package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchitectureDependencyServiceTest {

    private static final Origin ORIGIN = new Origin(
            "minos", "RELATIONSHIP_DERIVATION", "M3", "run", OriginType.DERIVED_BY_MINOS);

    private final ArchitectureDependencyService service = new ArchitectureDependencyService();

    @Test
    void aggregatesPersistedDependenciesBetweenModulesWithoutInventingEdges() {
        UUID projectId = UUID.randomUUID();
        ProjectDiscovery discovery = discovery();

        Symbol apiOne = symbol(projectId, "api-one", "api/src/main/java/com/acme/api/ApiOne.java", false);
        Symbol apiTwo = symbol(projectId, "api-two", "api/src/main/java/com/acme/api/ApiTwo.java", false);
        Symbol appOne = symbol(projectId, "app-one", "app/src/main/java/com/acme/app/AppOne.java", false);
        Symbol appTwo = symbol(projectId, "app-two", "app/src/main/java/com/acme/app/AppTwo.java", false);
        Symbol unknown = symbol(projectId, "unknown", null, false);
        Symbol external = symbol(projectId, "external", null, true);

        List<Relationship> relationships = List.of(
                dependency(projectId, "d1", appOne, apiOne),
                dependency(projectId, "d2", appTwo, apiOne),
                dependency(projectId, "d3", appOne, apiTwo),
                dependency(projectId, "d4", appOne, appTwo),
                dependency(projectId, "d5", appOne, unknown),
                dependency(projectId, "d6", appOne, external),
                references(projectId, "ignored-reference", appOne, apiOne)
        );
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot-m6-dependencies",
                List.of(apiOne, apiTwo, appOne, appTwo, unknown, external),
                List.of(),
                relationships
        );

        ArchitectureDependencyGraph graph = service.build(discovery, snapshot);

        assertEquals(6, graph.totalDependencyCount());
        assertEquals(3, graph.interModuleDependencyCount());
        assertEquals(1, graph.intraModuleDependencyCount());
        assertEquals(2, graph.unassignedDependencyCount());
        assertEquals(1, graph.moduleEdgeCount());
        assertEquals(InformationNature.DERIVED, graph.nature());
        assertFalse(graph.evidence().isEmpty());

        ArchitectureModuleDependency edge = graph.dependencies().getFirst();
        assertEquals(3, edge.dependencyCount());
        assertEquals(2, edge.sourceSymbolCount());
        assertEquals(2, edge.targetSymbolCount());
        assertEquals(List.of("rel:d1", "rel:d2", "rel:d3"), edge.sampleDependencyIds());
        assertEquals(1.0, edge.confidence());
        assertEquals(InformationNature.DERIVED, edge.nature());
        assertEquals(CodeEntityType.MODULE, edge.evidence().getFirst().source().type());
        assertEquals(CodeEntityType.MODULE, edge.evidence().getFirst().target().type());
    }

    @Test
    void producesSameGraphRegardlessOfInputOrder() {
        UUID projectId = UUID.randomUUID();
        ProjectDiscovery discovery = discovery();
        Symbol api = symbol(projectId, "api", "api/src/main/java/com/acme/api/Api.java", false);
        Symbol app = symbol(projectId, "app", "app/src/main/java/com/acme/app/App.java", false);
        Relationship dependency = dependency(projectId, "dependency", app, api);

        List<Symbol> symbols = new ArrayList<>(List.of(api, app));
        List<Relationship> relationships = new ArrayList<>(List.of(dependency));
        CodeKnowledgeSnapshot first = new CodeKnowledgeSnapshot(
                projectId, "snapshot-deterministic", symbols, List.of(), relationships);

        Collections.reverse(symbols);
        Collections.reverse(relationships);
        CodeKnowledgeSnapshot second = new CodeKnowledgeSnapshot(
                projectId, "snapshot-deterministic", symbols, List.of(), relationships);

        assertEquals(service.build(discovery, first), service.build(discovery, second));
    }

    @Test
    void countsUnresolvedPersistedDependencyAsUnassigned() {
        UUID projectId = UUID.randomUUID();
        Symbol app = symbol(projectId, "app", "app/src/main/java/com/acme/app/App.java", false);
        Relationship unresolved = new Relationship(
                "rel:unresolved",
                projectId.toString(),
                ref(app),
                null,
                "missing.Target",
                RelationshipKind.DEPENDS_ON,
                null,
                ResolutionStatus.UNRESOLVED,
                InformationNature.DERIVED,
                0.8,
                ORIGIN,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Persisted unresolved dependency",
                        ref(app),
                        null,
                        null,
                        0.8
                ))
        );
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot-unresolved",
                List.of(app),
                List.of(),
                List.of(unresolved)
        );

        ArchitectureDependencyGraph graph = service.build(discovery(), snapshot);

        assertEquals(1, graph.totalDependencyCount());
        assertEquals(0, graph.interModuleDependencyCount());
        assertEquals(0, graph.intraModuleDependencyCount());
        assertEquals(1, graph.unassignedDependencyCount());
        assertEquals(0, graph.moduleEdgeCount());
    }

    private static ProjectDiscovery discovery() {
        return new ProjectDiscovery(
                Path.of("."),
                "multi-module",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                List.of(
                        module("", "root", root("src/main/java")),
                        module("api", "api", root("api/src/main/java")),
                        module("app", "app", root("app/src/main/java"))
                )
        );
    }

    private static DiscoveredModule module(String path, String name, SourceRoot... roots) {
        return new DiscoveredModule(Path.of(path), name, Set.of(BuildSystem.MAVEN), List.of(roots));
    }

    private static SourceRoot root(String path) {
        return new SourceRoot(Path.of(path), SourceRootKind.SOURCE, Language.JAVA);
    }

    private static Symbol symbol(UUID projectId, String id, String fileId, boolean external) {
        return new Symbol(
                "sym:" + id,
                "key:" + id,
                external
                        ? SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK
                        : SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                null,
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
                external,
                false,
                Set.of()
        );
    }

    private static Relationship dependency(UUID projectId, String id, Symbol source, Symbol target) {
        return new Relationship(
                "rel:" + id,
                projectId.toString(),
                ref(source),
                ref(target),
                null,
                RelationshipKind.DEPENDS_ON,
                null,
                ResolutionStatus.RESOLVED,
                InformationNature.DERIVED,
                1.0,
                ORIGIN,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Direct dependency for test",
                        ref(source),
                        ref(target),
                        null,
                        1.0
                ))
        );
    }

    private static Relationship references(UUID projectId, String id, Symbol source, Symbol target) {
        return new Relationship(
                "rel:" + id,
                projectId.toString(),
                ref(source),
                ref(target),
                null,
                RelationshipKind.REFERENCES,
                null,
                ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL,
                null,
                new Origin("test", "TEST", "1", "run", OriginType.OTHER),
                List.of()
        );
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }
}
