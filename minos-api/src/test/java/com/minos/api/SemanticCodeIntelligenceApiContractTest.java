package com.minos.api;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticCodeIntelligenceApiContractTest {

    @Test
    void keepsHistoricalContractsAndSemanticCapabilityIsDisabledByDefault(@TempDir Path temp) throws Exception {
        MinosApplication application = MinosApplication.open(temp.resolve("home"));
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        application.projectRegistry().registerProject(projectRoot, "fixture");
        SemanticCodeIntelligenceApi api = new LocalSemanticCodeIntelligenceApi(application);

        assertEquals("1", MinosApi.CONTRACT_VERSION);
        assertEquals("1", AdvancedCodeIntelligenceApi.CONTRACT_VERSION);
        assertEquals("1", SemanticCodeIntelligenceApi.CONTRACT_VERSION);
        assertEquals("DISABLED", api.getSemanticIndexStatus("fixture").state());
        assertTrue(api.getSemanticIndexStatus("fixture").limitations()
                .contains("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticCodeIntelligenceApi.HybridQuery("query", 501, 0.0));
    }

    @Test
    void exposesExplicitIndexingSemanticHybridAndBoundedContext(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        MinosApplication baseline = MinosApplication.open(home);
        var project = baseline.projectRegistry().registerProject(projectRoot, "fixture");
        baseline.snapshotStore().publish(project.id(), "snapshot-1", List.of(symbol(project.id().toString())), List.of(), List.of());

        MinosApplication application = MinosApplication.builder(home)
                .embeddingProvider(new LocalHashEmbeddingProvider(64))
                .build();
        SemanticCodeIntelligenceApi api = new LocalSemanticCodeIntelligenceApi(application);

        var update = api.synchronizeSemanticIndex("fixture");
        assertEquals("READY", update.state());
        assertTrue(update.embeddedCount() > 0);
        assertTrue(update.indexSizeBytes() > 0);

        var semantic = api.semanticSearch("fixture",
                new SemanticCodeIntelligenceApi.SemanticQuery("GreetingService", 10, -1.0));
        assertFalse(semantic.hits().isEmpty());
        assertTrue(semantic.hits().stream().allMatch(hit -> "HEURISTIC".equals(hit.nature())));

        var hybrid = api.hybridSearch("fixture",
                new SemanticCodeIntelligenceApi.HybridQuery("GreetingService", 10, 0.0));
        assertTrue(hybrid.semanticAvailable());
        assertFalse(hybrid.hits().isEmpty());
        assertTrue(hybrid.hits().stream().flatMap(hit -> hit.signals().stream())
                .anyMatch(signal -> "SEMANTIC".equals(signal.type()) && "HEURISTIC".equals(signal.nature())));

        var context = api.buildHybridContext("fixture",
                new SemanticCodeIntelligenceApi.ContextQuery("GreetingService", 2, 256, 96));
        assertTrue(context.items().size() <= 2);
        assertTrue(context.usedTokens() <= 256);
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
