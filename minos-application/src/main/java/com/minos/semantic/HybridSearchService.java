package com.minos.semantic;

import com.minos.application.ProjectResolver;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Relationship;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Hybrid lexical + graph + optional semantic ranking. Structural facts remain authoritative. */
public final class HybridSearchService {

    public static final int MAX_RESULTS = 500;

    private final ProjectResolver projects;
    private final FileSymbolSnapshotStore snapshots;
    private final SemanticDocumentFactory documentFactory;
    private final SemanticIndexService semanticIndex;
    private final SemanticSearchService semanticSearch;

    public HybridSearchService(
            ProjectResolver projects,
            FileSymbolSnapshotStore snapshots,
            SemanticIndexService semanticIndex,
            SemanticSearchService semanticSearch
    ) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.semanticIndex = Objects.requireNonNull(semanticIndex, "semanticIndex");
        this.semanticSearch = Objects.requireNonNull(semanticSearch, "semanticSearch");
        this.documentFactory = new SemanticDocumentFactory();
    }

    public HybridResponse search(String projectReference, HybridRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        RegisteredProject project = projects.resolve(projectReference);
        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active knowledge snapshot: " + project.displayName()));
        List<SemanticDocument> documents = documentFactory.build(project, snapshot);
        Map<String, Double> semanticScores = semanticScores(projectReference, request, documents.size());
        boolean semanticAvailable = semanticIndex.status(projectReference).state() == SemanticIndexService.State.READY;
        Map<String, Integer> graphDegree = symbolGraphDegree(snapshot.relationships());
        int maxDegree = graphDegree.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<HybridHit> hits = new ArrayList<>();
        for (SemanticDocument document : documents) {
            double lexical = lexicalScore(request.query(), document.content());
            double semantic = semanticScores.getOrDefault(document.stableKey(), 0.0);
            double graph = document.kind() == SemanticDocumentKind.SYMBOL && maxDegree > 0
                    ? graphDegree.getOrDefault(document.sourceId(), 0) / (double) maxDegree
                    : 0.0;
            if (lexical <= 0.0 && semantic <= 0.0) continue;

            List<RankingSignal> signals = new ArrayList<>();
            if (lexical > 0.0) signals.add(new RankingSignal("LEXICAL", lexical, InformationNature.DERIVED));
            if (graph > 0.0) signals.add(new RankingSignal("GRAPH", graph, InformationNature.DERIVED));
            if (semanticAvailable) signals.add(new RankingSignal("SEMANTIC", semantic, InformationNature.HEURISTIC));
            double score = semanticAvailable
                    ? clamp01(0.50 * normalizedSemantic(semantic) + 0.35 * lexical + 0.15 * graph)
                    : clamp01(0.70 * lexical + 0.30 * graph);
            if (score >= request.minimumScore()) {
                hits.add(new HybridHit(document, score, lexical, graph, semantic,
                        semanticAvailable ? "LEXICAL_GRAPH_SEMANTIC" : "LEXICAL_GRAPH", signals));
            }
        }
        hits.sort(Comparator.comparingDouble(HybridHit::score).reversed()
                .thenComparing(hit -> hit.document().stableKey()));
        List<HybridHit> selected = hits.stream().limit(request.limit()).toList();
        List<String> limitations = new ArrayList<>();
        limitations.add("HYBRID_RANKING_IS_DERIVED_SELECTION_NOT_CODE_FACT");
        if (!semanticAvailable) limitations.add("SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED");
        else limitations.add("SEMANTIC_SIGNAL_IS_HEURISTIC_NOT_STRUCTURAL_FACT");
        return new HybridResponse(project.id().toString(), snapshot.snapshotId(), request.query(),
                semanticAvailable, selected, List.copyOf(limitations), elapsedMillis(started));
    }

    /** Lexical-only ranking retained as the M20 evaluation baseline. */
    public List<HybridHit> lexicalBaseline(String projectReference, String query, int limit) throws IOException {
        RegisteredProject project = projects.resolve(projectReference);
        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active knowledge snapshot: " + project.displayName()));
        List<HybridHit> hits = new ArrayList<>();
        for (SemanticDocument document : documentFactory.build(project, snapshot)) {
            double lexical = lexicalScore(query, document.content());
            if (lexical <= 0.0) continue;
            hits.add(new HybridHit(document, lexical, lexical, 0.0, 0.0, "LEXICAL",
                    List.of(new RankingSignal("LEXICAL", lexical, InformationNature.DERIVED))));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(HybridHit::score).reversed()
                        .thenComparing(hit -> hit.document().stableKey()))
                .limit(limit)
                .toList();
    }

    private Map<String, Double> semanticScores(String projectReference, HybridRequest request, int documentCount) throws IOException {
        if (semanticIndex.status(projectReference).state() != SemanticIndexService.State.READY) return Map.of();
        int probe = Math.min(SemanticSearchService.MAX_RESULTS,
                Math.max(request.limit() * 10, Math.min(documentCount, 200)));
        SemanticSearchService.SearchResponse semantic = semanticSearch.search(projectReference,
                new SemanticSearchService.SearchRequest(request.query(), Math.max(1, probe), -1.0));
        Map<String, Double> scores = new HashMap<>();
        for (SemanticSearchService.SearchHit hit : semantic.hits()) {
            scores.put(hit.document().stableKey(), hit.score());
        }
        return Map.copyOf(scores);
    }

    private static Map<String, Integer> symbolGraphDegree(List<Relationship> relationships) {
        Map<String, Integer> degree = new HashMap<>();
        for (Relationship relationship : relationships) {
            if (relationship.source().type() == CodeEntityType.SYMBOL) {
                degree.merge(relationship.source().id(), 1, Integer::sum);
            }
            if (relationship.target() != null && relationship.target().type() == CodeEntityType.SYMBOL) {
                degree.merge(relationship.target().id(), 1, Integer::sum);
            }
        }
        return degree;
    }

    static double lexicalScore(String query, String content) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return 0.0;
        String normalizedContent = normalize(content);
        Set<String> contentTerms = terms(normalizedContent);
        int matched = 0;
        for (String term : queryTerms) if (contentTerms.contains(term)) matched++;
        double overlap = matched / (double) queryTerms.size();
        String normalizedQuery = normalize(query);
        double phraseBonus = !normalizedQuery.isBlank() && normalizedContent.contains(normalizedQuery) ? 0.25 : 0.0;
        return clamp01(overlap + phraseBonus);
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        for (String term : normalize(value).split("\\s+")) {
            if (term.length() >= 2) terms.add(term);
        }
        return terms;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_]+", " ").trim();
    }

    private static double normalizedSemantic(double score) {
        // Cosine zero means no semantic support; negative similarities do not become a positive bonus.
        return clamp01(score);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public record HybridRequest(String query, int limit, double minimumScore) {
        public HybridRequest {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
            if (limit < 1 || limit > MAX_RESULTS) throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
            if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) {
                throw new IllegalArgumentException("minimumScore must be between 0 and 1");
            }
        }

        public static HybridRequest defaults(String query) {
            return new HybridRequest(query, 20, 0.0);
        }
    }

    public record RankingSignal(String type, double score, InformationNature nature) {
        public RankingSignal {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
            if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
            Objects.requireNonNull(nature, "nature");
        }
    }

    public record HybridHit(
            SemanticDocument document,
            double score,
            double lexicalScore,
            double graphScore,
            double semanticScore,
            String rankingMode,
            List<RankingSignal> signals
    ) {
        public HybridHit {
            Objects.requireNonNull(document, "document");
            if (rankingMode == null || rankingMode.isBlank()) throw new IllegalArgumentException("rankingMode must not be blank");
            signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        }
    }

    public record HybridResponse(
            String projectId,
            String snapshotId,
            String query,
            boolean semanticAvailable,
            List<HybridHit> hits,
            List<String> limitations,
            long latencyMillis
    ) {
        public HybridResponse {
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }
}
