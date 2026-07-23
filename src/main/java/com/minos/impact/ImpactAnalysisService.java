package com.minos.impact;

import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.store.CodeKnowledgeSnapshot;

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

/**
 * Analyse d'impact potentiel par traversée inverse, bornée et explicable, du graphe MINOS.
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

    private static final Comparator<Relationship> RELATIONSHIP_ORDER = Comparator
            .comparing((Relationship relationship) -> relationship.source().id())
            .thenComparing(Relationship::kind)
            .thenComparing(Relationship::id);

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt(Candidate::depth)
            .thenComparing(Comparator.comparingDouble(Candidate::confidence).reversed())
            .thenComparing(Candidate::pathSignature)
            .thenComparing(Candidate::symbolId);

    public ImpactAnalysisReport analyze(CodeKnowledgeSnapshot snapshot, ImpactAnalysisRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");

        Map<String, Symbol> symbolsById = new HashMap<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (symbolsById.put(symbol.id(), symbol) != null) {
                throw new IllegalArgumentException("duplicate symbol id in snapshot: " + symbol.id());
            }
        }

        Symbol root = symbolsById.get(request.symbolId());
        if (root == null) {
            throw new IllegalArgumentException("unknown symbol in snapshot: " + request.symbolId());
        }
        if (root.external()) {
            throw new IllegalArgumentException("impact root must be a local symbol: " + request.symbolId());
        }

        Map<String, List<Relationship>> incoming = incomingRelationships(snapshot, symbolsById);
        Candidate rootCandidate = Candidate.root(root.id());
        PriorityQueue<Candidate> queue = new PriorityQueue<>(CANDIDATE_ORDER);
        queue.add(rootCandidate);

        Map<String, Candidate> selected = new HashMap<>();
        Map<String, Candidate> testCandidates = new HashMap<>();
        selected.put(root.id(), rootCandidate);
        EnumSet<ImpactLimitation> limitations = baselineLimitations(snapshot, symbolsById);

        while (!queue.isEmpty()) {
            Candidate current = queue.poll();
            Candidate bestKnown = selected.get(current.symbolId());
            if (bestKnown != current && compareCandidate(current, bestKnown) > 0) {
                continue;
            }

            if (current.depth() >= request.maxDepth()) {
                if (hasFurtherImpact(incoming.getOrDefault(current.symbolId(), List.of()), selected)) {
                    limitations.add(ImpactLimitation.MAX_DEPTH_REACHED);
                }
                continue;
            }

            for (Relationship relationship : incoming.getOrDefault(current.symbolId(), List.of())) {
                String impactedId = relationship.source().id();
                if (impactedId.equals(root.id())) {
                    continue;
                }
                Symbol impacted = symbolsById.get(impactedId);
                if (impacted == null || impacted.external() || impacted.generated()) {
                    limitations.add(ImpactLimitation.EXTERNAL_TARGETS_NOT_TRAVERSED);
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
                    symbolsById.get(candidate.symbolId()),
                    candidate,
                    testCandidates.containsKey(candidate.symbolId())
            ));
        }

        List<ImpactedSymbol> impactedTests = testCandidates.values().stream()
                .filter(candidate -> includedIds.contains(candidate.symbolId()))
                .sorted(CANDIDATE_ORDER)
                .map(candidate -> toImpactedSymbol(symbolsById.get(candidate.symbolId()), candidate, true))
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

    private static Map<String, List<Relationship>> incomingRelationships(
            CodeKnowledgeSnapshot snapshot,
            Map<String, Symbol> symbolsById
    ) {
        Map<String, List<Relationship>> incoming = new HashMap<>();
        snapshot.relationships().stream()
                .filter(Objects::nonNull)
                .filter(relationship -> PROPAGATING_KINDS.contains(relationship.kind()))
                .filter(ImpactAnalysisService::isTraversableResolution)
                .filter(relationship -> relationship.target() != null)
                .filter(relationship -> relationship.source().type() == CodeEntityType.SYMBOL)
                .filter(relationship -> relationship.target().type() == CodeEntityType.SYMBOL)
                .filter(relationship -> symbolsById.containsKey(relationship.source().id()))
                .filter(relationship -> symbolsById.containsKey(relationship.target().id()))
                .sorted(RELATIONSHIP_ORDER)
                .forEach(relationship -> incoming
                        .computeIfAbsent(relationship.target().id(), ignored -> new ArrayList<>())
                        .add(relationship));
        incoming.replaceAll((ignored, relationships) -> relationships.stream()
                .sorted(RELATIONSHIP_ORDER)
                .toList());
        return incoming;
    }

    private static boolean isTraversableResolution(Relationship relationship) {
        return relationship.resolutionStatus() == ResolutionStatus.RESOLVED
                || (relationship.kind() == RelationshipKind.RELATED_TEST
                && relationship.resolutionStatus() == ResolutionStatus.HEURISTIC);
    }

    private static EnumSet<ImpactLimitation> baselineLimitations(
            CodeKnowledgeSnapshot snapshot,
            Map<String, Symbol> symbolsById
    ) {
        EnumSet<ImpactLimitation> limitations = EnumSet.of(
                ImpactLimitation.DYNAMIC_DISPATCH_NOT_PROVEN,
                ImpactLimitation.REFLECTION_NOT_PROVEN,
                ImpactLimitation.RUNTIME_CONFIGURATION_NOT_PROVEN
        );
        if (snapshot.relationships().stream().anyMatch(relationship ->
                PROPAGATING_KINDS.contains(relationship.kind())
                        && relationship.resolutionStatus() == ResolutionStatus.UNRESOLVED)) {
            limitations.add(ImpactLimitation.UNRESOLVED_RELATIONSHIPS_IGNORED);
        }
        if (snapshot.relationships().stream().anyMatch(relationship ->
                PROPAGATING_KINDS.contains(relationship.kind())
                        && relationship.target() != null
                        && (relationship.source().type() != CodeEntityType.SYMBOL
                        || relationship.target().type() != CodeEntityType.SYMBOL
                        || !symbolsById.containsKey(relationship.source().id())
                        || !symbolsById.containsKey(relationship.target().id())))) {
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
