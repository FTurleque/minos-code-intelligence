package com.minos.impact;

import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.query.SnapshotCoverageLimitations;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.InMemoryCodeKnowledgeStore.SymbolEdgeIndex;
import com.minos.store.SnapshotQueryView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * Analyse d'impact potentiel par traversée inverse, bornée et explicable, du graphe MINOS.
 *
 * <p>Sur une vue de requête, la table des symboles et l'index des arêtes entrantes sont ceux de la vue, construits
 * une fois pour elle et partagés par toutes les analyses (AUD-PERF-11) ; sur un snapshot seul, ils sont construits
 * pour l'appel. Les deux chemins rendent le même rapport, ordre compris.</p>
 */
public final class ImpactAnalysisService {

    private static final Set<RelationshipKind> PROPAGATING_KINDS = EnumSet.of(
            RelationshipKind.TYPE_DEFINITION,
            RelationshipKind.IMPORTS,
            RelationshipKind.REFERENCES,
            RelationshipKind.EXTENDS,
            RelationshipKind.IMPLEMENTS,
            RelationshipKind.CALLS,
            RelationshipKind.RETURNS,
            RelationshipKind.ACCEPTS,
            RelationshipKind.READS,
            RelationshipKind.WRITES,
            RelationshipKind.INSTANTIATES,
            RelationshipKind.DEPENDS_ON,
            RelationshipKind.INJECTS,
            RelationshipKind.RELATED_TEST
    );

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt(Candidate::depth)
            .thenComparing(Comparator.comparingDouble(Candidate::confidence).reversed())
            .thenComparing(Candidate::pathSignature)
            .thenComparing(Candidate::symbolId);

    /** Analyse sur une vue de requête : index et recherche de symbole de la vue, sans parcours du snapshot. */
    public ImpactAnalysisReport analyze(SnapshotQueryView view, ImpactAnalysisRequest request) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(request, "request");
        CodeKnowledgeSnapshot snapshot = view.snapshot();
        String projectId = snapshot.projectId().toString();
        InMemoryCodeKnowledgeStore store = view.queryStore();
        return analyze(snapshot, store.symbolEdgeIndex(projectId),
                symbolId -> store.findSymbolById(projectId, symbolId).orElse(null), request);
    }

    /** Analyse sur un snapshot seul : la table des symboles et l'index sont construits pour cet appel. */
    public ImpactAnalysisReport analyze(CodeKnowledgeSnapshot snapshot, ImpactAnalysisRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");

        Map<String, Symbol> symbolsById = new HashMap<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (symbolsById.put(symbol.id(), symbol) != null) {
                throw new IllegalArgumentException("duplicate symbol id in snapshot: " + symbol.id());
            }
        }
        return analyze(snapshot, SymbolEdgeIndex.of(snapshot), symbolsById::get, request);
    }

    private ImpactAnalysisReport analyze(
            CodeKnowledgeSnapshot snapshot,
            SymbolEdgeIndex edges,
            Function<String, Symbol> symbolsById,
            ImpactAnalysisRequest request
    ) {
        Symbol root = symbolsById.apply(request.symbolId());
        if (root == null) {
            throw new IllegalArgumentException("unknown symbol in snapshot: " + request.symbolId());
        }
        if (root.external() || root.generated()) {
            throw new IllegalArgumentException(
                    "impact root must be a local non-generated symbol: " + request.symbolId());
        }

        Candidate rootCandidate = Candidate.root(root.id());
        PriorityQueue<Candidate> queue = new PriorityQueue<>(CANDIDATE_ORDER);
        queue.add(rootCandidate);

        Map<String, Candidate> selected = new HashMap<>();
        Map<String, Candidate> testCandidates = new HashMap<>();
        selected.put(root.id(), rootCandidate);
        EnumSet<ImpactLimitation> limitations = baselineLimitations(snapshot, edges);

        while (!queue.isEmpty()) {
            Candidate current = queue.poll();
            Candidate bestKnown = selected.get(current.symbolId());
            if (bestKnown != current && compareCandidate(current, bestKnown) > 0) {
                continue;
            }

            List<Relationship> incoming = incomingRelationships(edges, current.symbolId(), symbolsById);
            if (current.depth() >= request.maxDepth()) {
                if (hasFurtherImpact(incoming, selected)) {
                    limitations.add(ImpactLimitation.MAX_DEPTH_REACHED);
                }
                continue;
            }

            for (Relationship relationship : incoming) {
                String impactedId = relationship.source().id();
                if (impactedId.equals(root.id())) {
                    continue;
                }
                Symbol impacted = symbolsById.apply(impactedId);
                if (impacted == null || impacted.external()) {
                    limitations.add(ImpactLimitation.EXTERNAL_TARGETS_NOT_TRAVERSED);
                    continue;
                }
                if (impacted.generated()) {
                    limitations.add(ImpactLimitation.GENERATED_SYMBOLS_NOT_TRAVERSED);
                    continue;
                }

                double edgeConfidence = relationshipConfidence(relationship);
                double pathConfidence = Math.min(current.confidence(), edgeConfidence);
                List<ImpactPathStep> path = append(
                        current.path(),
                        new ImpactPathStep(
                                current.symbolId(),
                                impactedId,
                                relationship.id(),
                                relationship.kind(),
                                relationship.nature(),
                                edgeConfidence
                        )
                );
                Candidate candidate = new Candidate(
                        impactedId,
                        current.depth() + 1,
                        pathConfidence,
                        path,
                        pathSignature(path)
                );

                if (relationship.kind() == RelationshipKind.RELATED_TEST) {
                    Candidate previousTest = testCandidates.get(impactedId);
                    if (previousTest == null || compareCandidate(candidate, previousTest) < 0) {
                        testCandidates.put(impactedId, candidate);
                    }
                }

                Candidate previous = selected.get(impactedId);
                if (previous == null || compareCandidate(candidate, previous) < 0) {
                    selected.put(impactedId, candidate);
                    queue.add(candidate);
                }
            }
        }

        selected.remove(root.id());
        List<Candidate> ordered = selected.values().stream()
                .sorted(CANDIDATE_ORDER)
                .toList();
        if (ordered.size() > request.maxResults()) {
            limitations.add(ImpactLimitation.MAX_RESULTS_REACHED);
            ordered = ordered.subList(0, request.maxResults());
        }

        Set<String> includedIds = new HashSet<>();
        List<ImpactedSymbol> impacts = new ArrayList<>();
        for (Candidate candidate : ordered) {
            includedIds.add(candidate.symbolId());
            impacts.add(toImpactedSymbol(
                    symbolsById.apply(candidate.symbolId()),
                    candidate,
                    testCandidates.containsKey(candidate.symbolId())
            ));
        }

        List<ImpactedSymbol> impactedTests = testCandidates.values().stream()
                .filter(candidate -> includedIds.contains(candidate.symbolId()))
                .sorted(CANDIDATE_ORDER)
                .map(candidate -> toImpactedSymbol(symbolsById.apply(candidate.symbolId()), candidate, true))
                .toList();

        return new ImpactAnalysisReport(
                snapshot.projectId(),
                snapshot.snapshotId(),
                root,
                request,
                impacts,
                impactedTests,
                List.copyOf(limitations)
        );
    }

    private static ImpactedSymbol toImpactedSymbol(Symbol symbol, Candidate candidate, boolean testImpact) {
        return new ImpactedSymbol(
                symbol,
                candidate.depth() == 1 ? ImpactLevel.DIRECT : ImpactLevel.INDIRECT,
                candidate.depth(),
                candidate.confidence(),
                candidate.path(),
                testImpact
        );
    }

    /**
     * Relations que la traversée suit vers {@code symbolId}, dans l'ordre de l'index (source, sorte, identifiant) :
     * le filtrage d'une liste triée la garde triée. La cible est {@code symbolId}, symbole connu du snapshot.
     */
    private static List<Relationship> incomingRelationships(
            SymbolEdgeIndex edges,
            String symbolId,
            Function<String, Symbol> symbolsById
    ) {
        List<Relationship> traversable = new ArrayList<>();
        for (Relationship relationship : edges.incoming(symbolId)) {
            if (PROPAGATING_KINDS.contains(relationship.kind())
                    && isTraversableResolution(relationship)
                    && relationship.source().type() == CodeEntityType.SYMBOL
                    && symbolsById.apply(relationship.source().id()) != null) {
                traversable.add(relationship);
            }
        }
        return traversable;
    }

    private static boolean isTraversableResolution(Relationship relationship) {
        return relationship.resolutionStatus() == ResolutionStatus.RESOLVED
                || (relationship.kind() == RelationshipKind.RELATED_TEST
                && relationship.resolutionStatus() == ResolutionStatus.HEURISTIC);
    }

    private static EnumSet<ImpactLimitation> baselineLimitations(
            CodeKnowledgeSnapshot snapshot,
            SymbolEdgeIndex edges
    ) {
        EnumSet<ImpactLimitation> limitations = EnumSet.of(
                ImpactLimitation.DYNAMIC_DISPATCH_NOT_PROVEN,
                ImpactLimitation.REFLECTION_NOT_PROVEN,
                ImpactLimitation.RUNTIME_CONFIGURATION_NOT_PROVEN
        );
        if (SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(snapshot)) {
            limitations.add(ImpactLimitation.OCCURRENCE_REFERENCES_NOT_PROJECTED);
        }
        if (edges.hasUnresolved(PROPAGATING_KINDS)) {
            limitations.add(ImpactLimitation.UNRESOLVED_RELATIONSHIPS_IGNORED);
        }
        if (edges.leavesProjectSymbols(PROPAGATING_KINDS)) {
            limitations.add(ImpactLimitation.EXTERNAL_TARGETS_NOT_TRAVERSED);
        }
        return limitations;
    }

    private static boolean hasFurtherImpact(List<Relationship> incoming, Map<String, Candidate> selected) {
        return incoming.stream().map(relationship -> relationship.source().id()).anyMatch(id -> !selected.containsKey(id));
    }

    private static double relationshipConfidence(Relationship relationship) {
        if (relationship.confidence() != null) {
            return relationship.confidence();
        }
        if (relationship.nature() == InformationNature.FACTUAL) {
            return 1.0;
        }
        throw new IllegalStateException("non-factual relationship without confidence: " + relationship.id());
    }

    private static int compareCandidate(Candidate left, Candidate right) {
        return CANDIDATE_ORDER.compare(left, right);
    }

    private static List<ImpactPathStep> append(List<ImpactPathStep> path, ImpactPathStep step) {
        List<ImpactPathStep> copy = new ArrayList<>(path.size() + 1);
        copy.addAll(path);
        copy.add(step);
        return List.copyOf(copy);
    }

    private static String pathSignature(List<ImpactPathStep> path) {
        return path.stream().map(ImpactPathStep::relationshipId).reduce((left, right) -> left + "\u0000" + right).orElse("");
    }

    private record Candidate(
            String symbolId,
            int depth,
            double confidence,
            List<ImpactPathStep> path,
            String pathSignature
    ) {
        private Candidate {
            Objects.requireNonNull(symbolId, "symbolId");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            Objects.requireNonNull(pathSignature, "pathSignature");
        }

        private static Candidate root(String symbolId) {
            return new Candidate(symbolId, 0, 1.0, List.of(), "");
        }
    }
}
