package com.minos.integration.nexus;

import com.minos.application.MinosApplication;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.semantic.LocalHashEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusSemanticSignalServiceTest {

    @Test
    void exportsCodeLocalSignalsWithoutTakingGlobalRankingOwnership(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        MinosApplication baseline = MinosApplication.open(home);
        var project = baseline.projectRegistry().registerProject(projectRoot, "fixture");
        baseline.snapshotStore().publish(project.id(), "snapshot-1", List.of(symbol(project.id().toString())), List.of(), List.of());

        MinosApplication application = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider(64))
                .build();
        application.semanticIndexService().synchronize("fixture");

        NexusSemanticSignalContract.Export export = new NexusSemanticSignalService(application)
                .export("fixture", "GreetingService", 10);

        assertEquals("2", export.contractVersion());
        assertEquals("MINOS", export.producer());
        assertTrue(export.semanticAvailable());
        assertFalse(export.candidates().isEmpty());
        assertEquals(NexusSemanticSignalService.RESPONSIBILITY_BOUNDARY, export.responsibilityBoundary());
        assertTrue(export.limitations().contains("NEXUS_GLOBAL_RANKING_NOT_PERFORMED_BY_MINOS"));
        assertTrue(export.limitations().contains("NEXUS_MULTI_SOURCE_CONTEXT_BUDGET_NOT_OWNED_BY_MINOS"));
        assertTrue(export.candidates().stream().flatMap(candidate -> candidate.signals().stream())
                .anyMatch(signal -> "SEMANTIC".equals(signal.type()) && "HEURISTIC".equals(signal.nature())));
    }

    private static Symbol symbol(String projectId) {
        return new Symbol(
                "greeting", "fixture:greeting", SymbolIdentityQuality.CANONICAL, projectId,
                "module", "src/Greeting.java", null, SymbolKind.CLASS,
                "GreetingService", "fixture.GreetingService", "class GreetingService", "java", null,
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run-m20", OriginType.OTHER),
                false, false, Set.of());
    }
}
