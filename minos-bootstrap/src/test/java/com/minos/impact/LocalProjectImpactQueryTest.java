package com.minos.impact;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
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

class LocalProjectImpactQueryTest {

    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void analyzesTheActiveKnowledgeSnapshotOfARegisteredProject(@TempDir Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "impact-fixture");
        Symbol dependency = symbol(project, "dependency");
        Symbol dependent = symbol(project, "dependent");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(
                project.id(),
                "snapshot-m8-local",
                List.of(dependency, dependent),
                List.of(),
                List.of(new Relationship(
                        "dependent-calls-dependency",
                        project.id().toString(),
                        ref(dependent),
                        ref(dependency),
                        null,
                        RelationshipKind.CALLS,
                        null,
                        ResolutionStatus.RESOLVED,
                        InformationNature.FACTUAL,
                        null,
                        ORIGIN,
                        List.of()
                ))
        );

        ImpactAnalysisReport report = new LocalProjectImpactQuery(registry, snapshots)
                .analyzeImpact("impact-fixture", dependency.id());

        assertEquals("snapshot-m8-local", report.snapshotId());
        assertEquals(dependency.id(), report.rootSymbol().id());
        assertEquals(List.of(dependent.id()), report.impacts().stream()
                .map(impact -> impact.symbol().id()).toList());
    }

    private static Symbol symbol(RegisteredProject project, String id) {
        return new Symbol(
                id,
                "key:" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                null,
                "src/main/java/" + id + ".java",
                null,
                SymbolKind.CLASS,
                id,
                "com.acme." + id,
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                ORIGIN,
                false,
                false,
                Set.of()
        );
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }
}
