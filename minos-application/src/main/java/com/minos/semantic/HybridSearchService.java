package com.minos.semantic;

import com.minos.application.ProjectResolver;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Relationship;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Hybrid lexical + graph + optional semantic ranking. Structural facts remain authoritative. */
public final class HybridSearchService {
    public static final int MAX_RESULTS = 500;
    private final ProjectResolver projects;
    private final CodeKnowledgeSnapshotStore snapshots;
    private final SemanticDocumentFactory documentFactory;
    private final SemanticIndexService semanticIndex;
    private final SemanticSearchService semanticSearch;
    private final ConcurrentHashMap<UUID, CachedCorpus> corpusCache = new ConcurrentHashMap<>();

    public HybridSearchService(ProjectResolver projects, CodeKnowledgeSnapshotStore snapshots,
                               SemanticIndexService semanticIndex, SemanticSearchService semanticSearch) {
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
        SemanticIndexService.Status semanticStatus = semanticIndex.status(projectReference);
        boolean semanticAvailable = semanticStatus.state() == SemanticIndexService.State.READY;
        List<SemanticDocument> indexedDocuments = semanticAvailable
                ? semanticIndex.activeIndex(projectReference).orElseThrow().documents().stream()
                    .map(SemanticVectorStore.IndexedDocument::document).toList()
                : null;
        CachedCorpus corpus = corpus(project, snapshot, indexedDocuments);
        Map<String, Double> semanticScores = semanticScores(projectReference, request, corpus.documents().size(), semanticAvailable);
        LexicalQuery lexicalQuery = LexicalQuery.compile(request.query());

        List<HybridHit> hits = new ArrayList<>();
        for (SemanticDocument document : corpus.documents()) {
            double lexical = lexicalQuery.score(document.content());
            double semantic = semanticScores.getOrDefault(document.stableKey(), 0.0);
            double graph = document.kind() == SemanticDocumentKind.SYMBOL && corpus.maxDegree() > 0
                    ? corpus.graphDegree().getOrDefault(document.sourceId(), 0) / (double) corpus.maxDegree() : 0.0;
            if (lexical <= 0.0 && semantic <= 0.0) continue;
            List<RankingSignal> signals = new ArrayList<>();
            if (lexical > 0.0) signals.add(new RankingSignal("LEXICAL", lexical, InformationNature.DERIVED));
            if (graph > 0.0) signals.add(new RankingSignal("GRAPH", graph, InformationNature.DERIVED));
            if (semanticAvailable) signals.add(new RankingSignal("SEMANTIC", semantic, InformationNature.HEURISTIC));
            double score = semanticAvailable
                    ? clamp01(0.50 * normalizedSemantic(semantic) + 0.35 * lexical + 0.15 * graph)
                    : clamp01(0.70 * lexical + 0.30 * graph);
            if (score >= request.minimumScore()) hits.add(new HybridHit(document, score, lexical, graph, semantic,
                    semanticAvailable ? "LEXICAL_GRAPH_SEMANTIC" : "LEXICAL_GRAPH", signals));
        }
        hits.sort(Comparator.comparingDouble(HybridHit::score).reversed().thenComparing(hit -> hit.document().stableKey()));
        List<String> limitations = new ArrayList<>();
        limitations.add("HYBRID_RANKING_IS_DERIVED_SELECTION_NOT_CODE_FACT");
        limitations.add(semanticAvailable ? "SEMANTIC_SIGNAL_IS_HEURISTIC_NOT_STRUCTURAL_FACT"
                : "SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED");
        return new HybridResponse(project.id().toString(), snapshot.snapshotId(), request.query(), semanticAvailable,
                hits.stream().limit(request.limit()).toList(), List.copyOf(limitations), elapsedMillis(started));
    }

    public List<HybridHit> lexicalBaseline(String projectReference, String query, int limit) throws IOException {
        RegisteredProject project = projects.resolve(projectReference);
        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active knowledge snapshot: " + project.displayName()));
        CachedCorpus corpus = corpus(project, snapshot, null);
        LexicalQuery lexicalQuery = LexicalQuery.compile(query);
        List<HybridHit> hits = new ArrayList<>();
        for (SemanticDocument document : corpus.documents()) {
            double lexical = lexicalQuery.score(document.content());
            if (lexical > 0.0) hits.add(new HybridHit(document, lexical, lexical, 0.0, 0.0, "LEXICAL",
                    List.of(new RankingSignal("LEXICAL", lexical, InformationNature.DERIVED))));
        }
        return hits.stream().sorted(Comparator.comparingDouble(HybridHit::score).reversed()
                .thenComparing(hit -> hit.document().stableKey())).limit(limit).toList();
    }

    private CachedCorpus corpus(RegisteredProject project, CodeKnowledgeSnapshot snapshot,
                                List<SemanticDocument> indexedDocuments) throws IOException {
        CachedCorpus cached = corpusCache.get(project.id());
        if (cached != null && cached.snapshotId().equals(snapshot.snapshotId())) return cached;
        List<SemanticDocument> documents = indexedDocuments != null ? List.copyOf(indexedDocuments) : documentFactory.build(project, snapshot);
        Map<String, Integer> graphDegree = Map.copyOf(symbolGraphDegree(snapshot.relationships()));
        int maxDegree = graphDegree.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        CachedCorpus next = new CachedCorpus(snapshot.snapshotId(), documents, graphDegree, maxDegree);
        corpusCache.put(project.id(), next); return next;
    }

    private Map<String, Double> semanticScores(String projectReference, HybridRequest request, int documentCount,
                                               boolean semanticAvailable) throws IOException {
        if (!semanticAvailable) return Map.of();
        int probe = Math.min(SemanticSearchService.MAX_RESULTS, Math.max(request.limit() * 10, Math.min(documentCount, 200)));
        SemanticSearchService.SearchResponse semantic = semanticSearch.search(projectReference,
                new SemanticSearchService.SearchRequest(request.query(), Math.max(1, probe), -1.0));
        Map<String, Double> scores = new HashMap<>();
        for (SemanticSearchService.SearchHit hit : semantic.hits()) scores.put(hit.document().stableKey(), hit.score());
        return Map.copyOf(scores);
    }

    private static Map<String, Integer> symbolGraphDegree(List<Relationship> relationships) {
        Map<String, Integer> degree = new HashMap<>();
        for (Relationship relationship : relationships) {
            if (relationship.source().type() == CodeEntityType.SYMBOL) degree.merge(relationship.source().id(), 1, Integer::sum);
            if (relationship.target() != null && relationship.target().type() == CodeEntityType.SYMBOL) degree.merge(relationship.target().id(), 1, Integer::sum);
        }
        return degree;
    }

    static double lexicalScore(String query, String content) { return LexicalQuery.compile(query).score(content); }
    private static Set<String> terms(String normalized) {
        Set<String> terms = new HashSet<>();
        if (normalized.isBlank()) return terms;
        for (String term : normalized.split("\\s+")) {
            if (term.length() >= 2) terms.add(term);
        }
        return terms;
    }
    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        boolean separating = false;
        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset); offset += Character.charCount(codePoint);
            if (isTermCodePoint(codePoint)) { normalized.appendCodePoint(codePoint); separating = false; }
            else if (!separating && !normalized.isEmpty()) { normalized.append(' '); separating = true; }
        }
        int length = normalized.length(); if (length > 0 && normalized.charAt(length - 1) == ' ') normalized.setLength(length - 1);
        return normalized.toString();
    }
    private static boolean isTermCodePoint(int codePoint) {
        if (codePoint == '_' || Character.isLetter(codePoint)) return true;
        int type = Character.getType(codePoint);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }
    private static boolean containsTerm(String normalizedContent, String term) {
        int from = 0;
        while (from <= normalizedContent.length() - term.length()) {
            int index = normalizedContent.indexOf(term, from); if (index < 0) return false; int end = index + term.length();
            boolean leftBoundary = index == 0 || normalizedContent.charAt(index - 1) == ' ';
            boolean rightBoundary = end == normalizedContent.length() || normalizedContent.charAt(end) == ' ';
            if (leftBoundary && rightBoundary) return true; from = index + 1;
        }
        return false;
    }
    private static double normalizedSemantic(double score) { return clamp01(score); }
    private static double clamp01(double value) { return Math.max(0.0, Math.min(1.0, value)); }
    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000L; }

    private record CachedCorpus(String snapshotId, List<SemanticDocument> documents, Map<String, Integer> graphDegree, int maxDegree) {
        CachedCorpus { documents = List.copyOf(documents); graphDegree = Map.copyOf(graphDegree); }
    }
    private record LexicalQuery(Set<String> terms, String normalizedQuery) {
        static LexicalQuery compile(String query) { String normalized = normalize(query); return new LexicalQuery(Set.copyOf(HybridSearchService.terms(normalized)), normalized); }
        double score(String content) {
            if (terms.isEmpty()) return 0.0; String normalizedContent = normalize(content); int matched = 0;
            for (String term : terms) if (containsTerm(normalizedContent, term)) matched++;
            double overlap = matched / (double) terms.size();
            double phraseBonus = !normalizedQuery.isBlank() && normalizedContent.contains(normalizedQuery) ? 0.25 : 0.0;
            return clamp01(overlap + phraseBonus);
        }
    }

    public record HybridRequest(String query, int limit, double minimumScore) {
        public HybridRequest {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
            if (limit < 1 || limit > MAX_RESULTS) throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
            if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) throw new IllegalArgumentException("minimumScore must be between 0 and 1");
        }
        public static HybridRequest defaults(String query) { return new HybridRequest(query, 20, 0.0); }
    }
    public record RankingSignal(String type, double score, InformationNature nature) {
        public RankingSignal {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
            if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite"); Objects.requireNonNull(nature, "nature");
        }
    }
    public record HybridHit(SemanticDocument document, double score, double lexicalScore, double graphScore,
                            double semanticScore, String rankingMode, List<RankingSignal> signals) {
        public HybridHit {
            Objects.requireNonNull(document, "document");
            if (rankingMode == null || rankingMode.isBlank()) throw new IllegalArgumentException("rankingMode must not be blank");
            signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        }
    }
    public record HybridResponse(String projectId, String snapshotId, String query, boolean semanticAvailable,
                                 List<HybridHit> hits, List<String> limitations, long latencyMillis) {
        public HybridResponse { hits = List.copyOf(Objects.requireNonNull(hits, "hits")); limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations")); }
    }
}
