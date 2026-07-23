package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchitectureTopologyServiceTest {

    private static final Origin ORIGIN = new Origin(
            "test-provider", "TEST", "1", "run", OriginType.OTHER);

    private final ArchitectureTopologyService service = new ArchitectureTopologyService();

    @Test
    void derivesMultiModuleTopologyFromDiscoveryAndSnapshot() {
        UUID projectId = UUID.randomUUID();
        ProjectDiscovery discovery = new ProjectDiscovery(
                Path.of("."),
                "multi-module",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                List.of(
                        module("", "root", BuildSystem.MAVEN,
                                root("src/main/java", SourceRootKind.SOURCE, Language.JAVA)),
                        module("api", "api", BuildSystem.MAVEN,
                                root("api/src/main/java", SourceRootKind.SOURCE, Language.JAVA)),
                        module("app", "app", BuildSystem.MAVEN,
                                root("app/src/main/java", SourceRootKind.SOURCE, Language.JAVA),
                                root("app/src/test/java", SourceRootKind.TEST, Language.JAVA))
                )
        );
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot-m6",
                List.of(
                        symbol(projectId, "api", "api/src/main/java/com/acme/api/User.java", "java", false),
                        symbol(projectId, "app", "app/src/main/java/com/acme/app/App.java", "java", false),
                        symbol(projectId, "app-test", "app/src/test/java/com/acme/app/AppTest.java", "java", false),
                        symbol(projectId, "unassigned", null, "java", false),
                        symbol(projectId, "external", null, "java", true)
                ),
                List.of(),
                List.of()
        );

        ArchitectureOverview overview = service.build(discovery, snapshot);

        assertEquals(projectId.toString(), overview.projectId());
        assertEquals("snapshot-m6", overview.snapshotId());
        assertEquals(3, overview.moduleCount());
        assertEquals(4, overview.localSymbolCount());
        assertEquals(1, overview.externalSymbolCount());
        assertEquals(1, overview.unassignedLocalSymbolCount());
        assertEquals(InformationNature.DERIVED, overview.nature());
        assertFalse(overview.evidence().isEmpty());

        ArchitectureModule api = module(overview, "api");
        assertEquals(1, api.symbolCount());
        assertEquals(List.of("JAVA"), api.languages());
        assertEquals(List.of("MAVEN"), api.buildSystems());
        assertEquals(InformationNature.FACTUAL, api.nature());
        assertEquals(InformationNature.DERIVED, api.aggregateNature());
        assertEquals("com.acme.api", api.namespaces().getFirst().name());

        ArchitectureModule app = module(overview, "app");
        assertEquals(2, app.symbolCount());
        assertEquals(1, app.namespaceCount());
        ArchitectureNamespace appNamespace = app.namespaces().getFirst();
        assertEquals("com.acme.app", appNamespace.name());
        assertEquals(2, appNamespace.symbolCount());
        assertEquals(InformationNature.DERIVED, appNamespace.nature());
        assertFalse(appNamespace.evidence().isEmpty());
    }

    @Test
    void exposesDefaultNamespaceAndRejectsUnsafeFileIdsFromAggregation() {
        UUID projectId = UUID.randomUUID();
        ProjectDiscovery discovery = new ProjectDiscovery(
                Path.of("."),
                "typescript",
                Set.of(Language.TYPESCRIPT),
                Set.of(BuildSystem.NPM),
                List.of(module("", "typescript", BuildSystem.NPM,
                        root("src", SourceRootKind.SOURCE, Language.TYPESCRIPT)))
        );
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot-default",
                List.of(
                        symbol(projectId, "app", "src/app.ts", "typescript", false),
                        symbol(projectId, "unsafe", "../outside.ts", "typescript", false)
                ),
                List.of(),
                List.of()
        );

        ArchitectureOverview overview = service.build(discovery, snapshot);

        assertEquals(2, overview.localSymbolCount());
        assertEquals(1, overview.unassignedLocalSymbolCount());
        ArchitectureModule root = module(overview, "");
        assertEquals(1, root.symbolCount());
        assertEquals("<default>", root.namespaces().getFirst().name());
        assertEquals("", root.namespaces().getFirst().relativePath());
    }

    @Test
    void producesTheSameTopologyRegardlessOfSnapshotSymbolOrder() {
        UUID projectId = UUID.randomUUID();
        ProjectDiscovery discovery = new ProjectDiscovery(
                Path.of("."),
                "deterministic",
                Set.of(Language.TYPESCRIPT),
                Set.of(BuildSystem.NPM),
                List.of(module("", "deterministic", BuildSystem.NPM,
                        root("src", SourceRootKind.SOURCE, Language.TYPESCRIPT)))
        );
        Symbol alpha = symbol(projectId, "alpha", "src/domain/Alpha.ts", "typescript", false);
        Symbol zeta = symbol(projectId, "zeta", "src/domain/Zeta.ts", "typescript", false);

        ArchitectureOverview first = service.build(
                discovery,
                new CodeKnowledgeSnapshot(
                        projectId, "same-snapshot", List.of(zeta, alpha), List.of(), List.of()));
        ArchitectureOverview second = service.build(
                discovery,
                new CodeKnowledgeSnapshot(
                        projectId, "same-snapshot", List.of(alpha, zeta), List.of(), List.of()));

        assertEquals(first, second);
    }

    private static DiscoveredModule module(
            String path,
            String name,
            BuildSystem buildSystem,
            SourceRoot... roots
    ) {
        return new DiscoveredModule(Path.of(path), name, Set.of(buildSystem), List.of(roots));
    }

    private static SourceRoot root(String path, SourceRootKind kind, Language language) {
        return new SourceRoot(Path.of(path), kind, language);
    }

    private static ArchitectureModule module(ArchitectureOverview overview, String relativePath) {
        Optional<ArchitectureModule> match = overview.modules().stream()
                .filter(module -> relativePath.equals(module.relativePath()))
                .findFirst();
        return match.orElseThrow();
    }

    private static Symbol symbol(
            UUID projectId,
            String id,
            String fileId,
            String language,
            boolean external
    ) {
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
                language,
                null,
                ResolutionStatus.RESOLVED,
                ORIGIN,
                external,
                false,
                Set.of()
        );
    }
}
