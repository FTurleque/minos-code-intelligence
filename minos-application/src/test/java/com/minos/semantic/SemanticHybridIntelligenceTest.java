package com.minos.semantic;

import com.minos.application.MinosApplication;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHybridIntelligenceTest {

    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-m20", OriginType.OTHER);

    @Test
    void semanticIsOptionalAndHybridFallsBackToStructuredSignals(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        MinosApplication baseline = MinosApplication.builder(fixture.home()).build();
        RegisteredProject project = baseline.projectRegistry().registerProject(fixture.projectRoot(), "semantic-fixture");
        baseline.snapshotStore().publish(project.id(), "snapshot-1",
                symbols(project.id(), "()"), List.of(), List.of());

        SemanticIndexService.Status status = baseline.semanticIndexService().status("semantic-fixture");
        assertEquals(SemanticIndexService.State.DISABLED, status.state());
        assertTrue(status.limitations().contains("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));

        HybridSearchService.HybridResponse fallback = baseline.hybridSearchService().search(
                "semantic-fixture", new HybridSearchService.HybridRequest("JwtGuard", 5, 0.0));
        assertFalse(fallback.semanticAvailable());
        assertFalse(fallback.hits().isEmpty());
        assertTrue(fallback.hits().stream().allMatch(hit -> !hit.rankingMode().contains("SEMANTIC")));
        assertTrue(fallback.limitations().contains("SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED"));
    }

    @Test
    void nativeOpenCanExplicitlySelectBundledLocalHashProvider(@TempDir Path temp) throws Exception {
        String previous = System.getProperty(MinosApplication.SEMANTIC_PROVIDER_PROPERTY);
        try {
            System.setProperty(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, "local-hash");
            MinosApplication application = MinosApplication.open(temp.resolve("native-home"));
            assertEquals("minos-local-hash",
                    application.semanticIndexService().embeddingProvider().orElseThrow().id());
        } finally {
            if (previous == null) System.clearProperty(MinosApplication.SEMANTIC_PROVIDER_PROPERTY);
            else System.setProperty(MinosApplication.SEMANTIC_PROVIDER_PROPERTY, previous);
        }
    }

    @Test
    void controlledSemanticAndHybridRetrievalMeasureGainAndRespectContextBudget(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        MinosApplication baseline = MinosApplication.builder(fixture.home()).build();
        RegisteredProject project = baseline.projectRegistry().registerProject(fixture.projectRoot(), "semantic-fixture");
        baseline.snapshotStore().publish(project.id(), "snapshot-1",
                symbols(project.id(), "()"), List.of(), List.of());

        MinosApplication application = MinosApplication.builder(fixture.home())
                .embeddingProvider(new ConceptEmbeddingProvider())
                .build();
        SemanticIndexService.UpdateReport initial = application.semanticIndexService().synchronize("semantic-fixture");
        assertEquals(SemanticIndexService.State.READY, initial.state());
        assertTrue(initial.documentCount() >= 6);
        assertEquals(initial.documentCount(), initial.embeddedCount());
        assertTrue(initial.indexSizeBytes() > 0);

        SemanticSearchService.SearchResponse semantic = application.semanticSearchService().search(
                "semantic-fixture", new SemanticSearchService.SearchRequest("where is access enforced", 10, -1.0));
        assertFalse(semantic.hits().isEmpty());
        assertTrue(semantic.hits().stream().allMatch(hit -> hit.nature() == com.minos.domain.InformationNature.HEURISTIC));
        assertTrue(semantic.limitations().contains("VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT"));

        String relevant = "symbol:fixture:jwt";
        List<String> lexicalRanked = application.hybridSearchService()
                .lexicalBaseline("semantic-fixture", "where is access enforced", 10)
                .stream().map(hit -> hit.document().stableKey()).toList();
        HybridSearchService.HybridResponse hybrid = application.hybridSearchService().search(
                "semantic-fixture", new HybridSearchService.HybridRequest("where is access enforced", 10, 0.0));
        List<String> hybridRanked = hybrid.hits().stream().map(hit -> hit.document().stableKey()).toList();
        assertTrue(hybrid.semanticAvailable());
        assertTrue(hybrid.hits().stream().anyMatch(hit -> hit.signals().stream()
                .anyMatch(signal -> "SEMANTIC".equals(signal.type())
                        && signal.nature() == com.minos.domain.InformationNature.HEURISTIC)));

        SemanticSearchEvaluator evaluator = new SemanticSearchEvaluator();
        SemanticSearchEvaluator.Evaluation lexicalEvaluation = evaluator.evaluate(lexicalRanked, Set.of(relevant), 3);
        SemanticSearchEvaluator.Evaluation hybridEvaluation = evaluator.evaluate(hybridRanked, Set.of(relevant), 3);
        SemanticSearchEvaluator.Gain gain = evaluator.compare(lexicalEvaluation, hybridEvaluation);
        assertEquals(0.0, lexicalEvaluation.recallAtK());
        assertEquals(1.0, hybridEvaluation.recallAtK());
        assertTrue(hybridEvaluation.mrr() > lexicalEvaluation.mrr());
        assertTrue(hybridEvaluation.ndcgAtK() > lexicalEvaluation.ndcgAtK());
        assertTrue(gain.measurableGain());

        HybridContextBuilder.ContextResponse context = application.hybridContextBuilder().build(
                "semantic-fixture", new HybridContextBuilder.ContextRequest(
                        "where is access enforced", 2, 180, 80));
        assertTrue(context.items().size() <= 2);
        assertTrue(context.usedTokens() <= 180);
        assertTrue(context.items().stream().allMatch(item -> item.estimatedTokens() <= 80));
    }

    @Test
    void semanticIndexReusesUnchangedDocumentsAndTargetsChangedEmbeddings(@TempDir Path temp) throws Exception {
        Fixture fixture = fixture(temp);
        MinosApplication baseline = MinosApplication.builder(fixture.home()).build();
        RegisteredProject project = baseline.projectRegistry().registerProject(fixture.projectRoot(), "semantic-fixture");
        baseline.snapshotStore().publish(project.id(), "snapshot-1",
                symbols(project.id(), "()"), List.of(), List.of());

        MinosApplication application = MinosApplication.builder(fixture.home())
                .embeddingProvider(new ConceptEmbeddingProvider())
                .build();
        SemanticIndexService.UpdateReport first = application.semanticIndexService().synchronize("semantic-fixture");
        SemanticIndexService.UpdateReport unchanged = application.semanticIndexService().synchronize("semantic-fixture");
        assertEquals(0, unchanged.embeddedCount());
        assertEquals(first.documentCount(), unchanged.reused());

        baseline.snapshotStore().publish(project.id(), "snapshot-2",
                symbols(project.id(), "(Token token)"), List.of(), List.of());
        SemanticIndexService.Status stale = application.semanticIndexService().status("semantic-fixture");
        assertEquals(SemanticIndexService.State.STALE, stale.state());
        assertTrue(stale.limitations().contains("SEMANTIC_INDEX_SNAPSHOT_STALE"));

        SemanticIndexService.UpdateReport incremental = application.semanticIndexService().synchronize("semantic-fixture");
        assertEquals("snapshot-2", incremental.snapshotId());
        assertTrue(incremental.embeddedCount() > 0);
        assertTrue(incremental.embeddedCount() < incremental.documentCount());
        assertTrue(incremental.reused() > 0);
        assertEquals(0, incremental.removed());
        assertEquals(SemanticIndexService.State.READY,
                application.semanticIndexService().status("semantic-fixture").state());
    }

    private static Fixture fixture(Path temp) throws Exception {
        Path home = temp.resolve("minos-home");
        Path project = Files.createDirectories(temp.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src"));
        Files.writeString(src.resolve("Auth.java"), """
                class JwtGuard {
                    void verify() {
                        // authentication policy validates JWT expiration before protected access
                    }
                }
                """);
        Files.writeString(src.resolve("SnapshotRepository.java"), """
                class SnapshotRepository {
                    void persist() {
                        // persistence boundary stores active project snapshots
                    }
                }
                """);
        return new Fixture(home, project);
    }

    private static List<Symbol> symbols(java.util.UUID projectId, String jwtSignature) {
        return List.of(
                symbol(projectId, "jwt", "fixture:jwt", "JwtGuard", "fixture.JwtGuard.verify", jwtSignature,
                        "src/Auth.java", 1, 5),
                symbol(projectId, "snapshot", "fixture:snapshot", "SnapshotRepository", "fixture.SnapshotRepository.persist", "()",
                        "src/SnapshotRepository.java", 1, 5));
    }

    private static Symbol symbol(
            java.util.UUID projectId,
            String id,
            String symbolKey,
            String name,
            String qualifiedName,
            String signature,
            String fileId,
            int startLine,
            int endLine
    ) {
        return new Symbol(
                id, symbolKey, SymbolIdentityQuality.CANONICAL, projectId.toString(), "module", fileId, null,
                SymbolKind.METHOD, name, qualifiedName, signature, "java",
                new SymbolLocation(fileId, startLine, 0, endLine, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }

    private record Fixture(Path home, Path projectRoot) {
    }

    /** Controlled concept space used only as a ground truth for M20 relevance qualification. */
    private static final class ConceptEmbeddingProvider implements EmbeddingProvider {
        @Override public String id() { return "fixture-concepts"; }
        @Override public String modelId() { return "fixture-concepts-v1"; }
        @Override public int dimensions() { return 3; }

        @Override
        public SemanticVector embed(String stableKey, String text) {
            String value = text.toLowerCase(Locale.ROOT);
            double auth = containsAny(value, "access", "enforced", "authentication", "jwt", "token", "guard") ? 1.0 : 0.0;
            double persistence = containsAny(value, "persistence", "persist", "snapshot", "repository", "stores") ? 1.0 : 0.0;
            double impact = containsAny(value, "impact", "propagation", "dependency") ? 1.0 : 0.0;
            double norm = Math.sqrt(auth * auth + persistence * persistence + impact * impact);
            if (norm == 0.0) return new SemanticVector(stableKey, List.of(0.0, 0.0, 0.0));
            return new SemanticVector(stableKey, List.of(auth / norm, persistence / norm, impact / norm));
        }

        private static boolean containsAny(String value, String... terms) {
            for (String term : terms) if (value.contains(term)) return true;
            return false;
        }
    }
}
