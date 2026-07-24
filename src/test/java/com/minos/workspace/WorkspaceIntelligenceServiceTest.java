package com.minos.workspace;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceIntelligenceServiceTest {

    @Test
    void resolvesOnlyExactProviderIdentityAcrossWorkspaceProjects(@TempDir Path home) throws Exception {
        Path projectARoot = Files.createDirectories(home.resolve("project-a"));
        Path projectBRoot = Files.createDirectories(home.resolve("project-b"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));

        RegisteredProject projectA = registry.registerProject(projectARoot, "project-a");
        RegisteredProject projectB = registry.registerProject(projectBRoot, "project-b");
        RegisteredWorkspace workspace = registry.createWorkspace("platform");
        registry.assignProjectToWorkspace(projectA.id(), workspace.id());
        registry.assignProjectToWorkspace(projectB.id(), workspace.id());

        Origin origin = new Origin("scip-typescript", "SCIP", "0.4.0", "run-m12", OriginType.SCIP);
        String targetProviderId = "scip-typescript npm @example/api 1.0.0 api/GreetingPort#";

        Symbol source = symbol(
                "source-symbol", projectA.id().toString(), "Client", "Client",
                "scip-typescript npm @example/app 1.0.0 app/Client#", origin
        );
        Relationship exactProviderTarget = unresolved(
                "relationship-a-b",
                projectA.id().toString(),
                source.id(),
                targetProviderId,
                origin
        );
        Relationship nameOnlyTarget = unresolved(
                "relationship-name-only",
                projectA.id().toString(),
                source.id(),
                "GreetingPort",
                origin
        );
        snapshots.publish(
                projectA.id(),
                "snapshot-a",
                List.of(source),
                List.of(),
                List.of(exactProviderTarget, nameOnlyTarget)
        );

        Symbol target = symbol(
                "target-symbol", projectB.id().toString(), "GreetingPort", "GreetingPort",
                targetProviderId, origin
        );
        snapshots.publish(projectB.id(), "snapshot-b", List.of(target), List.of(), List.of());

        WorkspaceIntelligenceService service = new WorkspaceIntelligenceService(registry, snapshots);
        WorkspaceIntelligenceService.WorkspaceReport report = service.analyze("platform", 100);

        assertEquals(2, report.projects().size());
        assertEquals(1, report.exactResolutionCount());
        assertEquals(0, report.ambiguousTargetCount());
        assertEquals(1, report.unresolvedTargetCount());
        assertFalse(report.relationshipsTruncated());
        assertTrue(report.limitations().contains("UNRESOLVED_CROSS_REPOSITORY_TARGETS"));

        WorkspaceIntelligenceService.CrossRepositoryRelationship relation =
                report.crossRepositoryRelationships().getFirst();
        assertEquals(projectA.id().toString(), relation.sourceProjectId());
        assertEquals(projectB.id().toString(), relation.targetProjectId());
        assertEquals(target.id(), relation.targetSymbolId());
        assertEquals("GreetingPort", relation.targetQualifiedName());
        assertEquals("EXACT_PROVIDER_REFERENCE", relation.resolutionBasis());
        assertEquals(1.0, relation.confidence());
    }

    private static Relationship unresolved(
            String id,
            String projectId,
            String sourceSymbolId,
            String unresolvedTarget,
            Origin origin
    ) {
        return new Relationship(
                id,
                projectId,
                new CodeEntityRef(CodeEntityType.SYMBOL, sourceSymbolId),
                null,
                unresolvedTarget,
                RelationshipKind.REFERENCES,
                null,
                ResolutionStatus.UNRESOLVED,
                InformationNature.FACTUAL,
                null,
                origin,
                List.of()
        );
    }

    private static Symbol symbol(
            String id,
            String projectId,
            String name,
            String qualifiedName,
            String providerExternalId,
            Origin origin
    ) {
        return new Symbol(
                id,
                "key-" + id,
                SymbolIdentityQuality.CANONICAL,
                projectId,
                "module-main",
                "file-" + id,
                null,
                SymbolKind.INTERFACE,
                name,
                qualifiedName,
                null,
                "typescript",
                null,
                ResolutionStatus.RESOLVED,
                origin,
                false,
                false,
                Set.of(new ProviderReference(origin.providerId(), providerExternalId))
        );
    }
}
