package com.minos.impact;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactAnalysisServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000808");
    private static final Origin FACTUAL_ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    private static final Origin DERIVED_ORIGIN = new Origin("minos", "M8_TEST", "1", "run-1", OriginType.DERIVED_BY_MINOS);

    @Test
    void findsDirectIndirectAndHeuristicTestImpactsWithConservativeConfidence() {
        Symbol repository = symbol("repository", "src/main/java/Repository.java");
        Symbol service = symbol("service", "src/main/java/Service.java");
        Symbol controller = symbol("controller", "src/main/java/Controller.java");
        Symbol test = symbol("service-test", "src/test/java/ServiceTest.java");

        CodeKnowledgeSnapshot snapshot = snapshot(
                List.of(repository, service, controller, test),
                List.of(
                        factual("service-calls-repository", service, repository, RelationshipKind.CALLS),
                        derived("controller-depends-service", controller, service, RelationshipKind.DEPENDS_ON, 0.82),
                        heuristic("test-related-service", test, service, RelationshipKind.RELATED_TEST, 0.70),
                        factual("cycle", repository, controller, RelationshipKind.REFERENCES)
                )
        );

        ImpactAnalysisReport report = new ImpactAnalysisService().analyze(
                snapshot,
                new ImpactAnalysisRequest(repository.id(), 4, 20)
        );

        assertEquals(InformationNature.DERIVED, report.nature());
        assertTrue(report.impacts().stream().allMatch(impact -> impact.nature() == InformationNature.DERIVED));
        assertEquals(List.of(service.id(), controller.id(), test.id()), report.impacts().stream()
                .map(impact -> impact.symbol().id()).toList());
        assertEquals(ImpactLevel.DIRECT, report.impacts().getFirst().level());
        assertEquals(1.0, report.impacts().getFirst().confidence());
        assertEquals(ImpactLevel.INDIRECT, report.impacts().get(1).level());
        assertEquals(0.82, report.impacts().get(1).confidence());
        assertEquals(0.70, report.impacts().get(2).confidence());
        assertEquals(List.of(test.id()), report.potentiallyImpactedTests().stream()
                .map(impact -> impact.symbol().id()).toList());
        assertEquals(List.of("service-calls-repository", "test-related-service"),
                report.potentiallyImpactedTests().getFirst().path().stream()
                        .map(ImpactPathStep::relationshipId).toList());
        assertTrue(report.limitations().contains(ImpactLimitation.DYNAMIC_DISPATCH_NOT_PROVEN));
        assertTrue(report.limitations().contains(ImpactLimitation.REFLECTION_NOT_PROVEN));
        assertTrue(report.limitations().contains(ImpactLimitation.RUNTIME_CONFIGURATION_NOT_PROVEN));
    }

    @Test
    void choosesShortestPathThenHighestConfidenceAtEqualDepth() {
        Symbol root = symbol("root", "src/main/java/Root.java");
        Symbol intermediary = symbol("intermediary", "src/main/java/Intermediary.java");
        Symbol shortPath = symbol("short-path", "src/main/java/ShortPath.java");
        Symbol confidenceChoice = symbol("confidence-choice", "src/main/java/ConfidenceChoice.java");

        CodeKnowledgeSnapshot snapshot = snapshot(
                List.of(root, intermediary, shortPath, confidenceChoice),
                List.of(
                        factual("intermediary-root", intermediary, root, RelationshipKind.REFERENCES),
                        factual("short-indirect", shortPath, intermediary, RelationshipKind.CALLS),
                        derived("short-direct", shortPath, root, RelationshipKind.DEPENDS_ON, 0.50),
                        derived("confidence-low", confidenceChoice, root, RelationshipKind.REFERENCES, 0.40),
                        derived("confidence-high", confidenceChoice, root, RelationshipKind.REFERENCES, 0.90)
                )
        );

        ImpactAnalysisReport report = new ImpactAnalysisService().analyze(
                snapshot,
                new ImpactAnalysisRequest(root.id(), 4, 20)
        );

        ImpactedSymbol shortest = impact(report, shortPath.id());
        ImpactedSymbol strongest = impact(report, confidenceChoice.id());
        assertEquals(1, shortest.depth());
        assertEquals(0.50, shortest.confidence());
        assertEquals("short-direct", shortest.path().getFirst().relationshipId());
        assertEquals(1, strongest.depth());
        assertEquals(0.90, strongest.confidence());
        assertEquals("confidence-high", strongest.path().getFirst().relationshipId());
    }

    @Test
    void depthLimitStopsTraversalAndReportsTheLimit() {
        Symbol root = symbol("root", "src/main/java/Root.java");
        Symbol direct = symbol("direct", "src/main/java/Direct.java");
        Symbol indirect = symbol("indirect", "src/main/java/Indirect.java");
        CodeKnowledgeSnapshot snapshot = snapshot(
                List.of(root, direct, indirect),
                List.of(
                        factual("direct-root", direct, root, RelationshipKind.REFERENCES),
                        factual("indirect-direct", indirect, direct, RelationshipKind.CALLS)
                )
        );

        ImpactAnalysisReport report = new ImpactAnalysisService().analyze(
                snapshot,
                new ImpactAnalysisRequest(root.id(), 1, 20)
        );

        assertEquals(List.of(direct.id()), report.impacts().stream().map(impact -> impact.symbol().id()).toList());
        assertTrue(report.limitations().contains(ImpactLimitation.MAX_DEPTH_REACHED));
    }

    @Test
    void resultLimitIsDeterministicAndExplicit() {
        Symbol root = symbol("root", "src/main/java/Root.java");
        Symbol a = symbol("a", "src/main/java/A.java");
        Symbol b = symbol("b", "src/main/java/B.java");
        CodeKnowledgeSnapshot snapshot = snapshot(
                List.of(root, a, b),
                List.of(
                        factual("a-root", a, root, RelationshipKind.REFERENCES),
                        factual("b-root", b, root, RelationshipKind.REFERENCES)
                )
        );

        ImpactAnalysisReport report = new ImpactAnalysisService().analyze(
                snapshot,
                new ImpactAnalysisRequest(root.id(), 2, 1)
        );

        assertEquals(1, report.impacts().size());
        assertEquals(a.id(), report.impacts().getFirst().symbol().id());
        assertTrue(report.limitations().contains(ImpactLimitation.MAX_RESULTS_REACHED));
    }

    @Test
    void reportsUnresolvedAndGeneratedCoverageGapsWithoutTraversingThem() {
        Symbol root = symbol("root", "src/main/java/Root.java");
        Symbol generated = symbol("generated", "target/generated/Generated.java", true);
        Symbol unresolvedSource = symbol("unresolved-source", "src/main/java/UnresolvedSource.java");
        Relationship unresolved = new Relationship(
                "unresolved",
                PROJECT_ID.toString(),
                ref(unresolvedSource),
                null,
                "missing.Target",
                RelationshipKind.REFERENCES,
                null,
                ResolutionStatus.UNRESOLVED,
                InformationNature.FACTUAL,
                null,
                FACTUAL_ORIGIN,
                List.of()
        );
        CodeKnowledgeSnapshot snapshot = snapshot(
                List.of(root, generated, unresolvedSource),
                List.of(
                        factual("generated-root", generated, root, RelationshipKind.REFERENCES),
                        unresolved
                )
        );

        ImpactAnalysisReport report = new ImpactAnalysisService().analyze(
                snapshot,
                new ImpactAnalysisRequest(root.id(), 2, 20)
        );

        assertTrue(report.impacts().isEmpty());
        assertTrue(report.limitations().contains(ImpactLimitation.GENERATED_SYMBOLS_NOT_TRAVERSED));
        assertTrue(report.limitations().contains(ImpactLimitation.UNRESOLVED_RELATIONSHIPS_IGNORED));
    }

    private static ImpactedSymbol impact(ImpactAnalysisReport report, String symbolId) {
        return report.impacts().stream()
                .filter(impact -> symbolId.equals(impact.symbol().id()))
                .findFirst()
                .orElseThrow();
    }

    private static CodeKnowledgeSnapshot snapshot(List<Symbol> symbols, List<Relationship> relationships) {
        return new CodeKnowledgeSnapshot(PROJECT_ID, "snapshot-m8", symbols, List.of(), relationships);
    }

    private static Symbol symbol(String id, String fileId) {
        return symbol(id, fileId, false);
    }

    private static Symbol symbol(String id, String fileId, boolean generated) {
        return new Symbol(
                id,
                "key:" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                PROJECT_ID.toString(),
                null,
                fileId,
                null,
                SymbolKind.CLASS,
                id,
                "com.acme." + id,
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                FACTUAL_ORIGIN,
                false,
                generated,
                Set.of()
        );
    }

    private static Relationship factual(String id, Symbol source, Symbol target, RelationshipKind kind) {
        return new Relationship(
                id, PROJECT_ID.toString(), ref(source), ref(target), null, kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, FACTUAL_ORIGIN, List.of()
        );
    }

    private static Relationship derived(
            String id,
            Symbol source,
            Symbol target,
            RelationshipKind kind,
            double confidence
    ) {
        return new Relationship(
                id, PROJECT_ID.toString(), ref(source), ref(target), null, kind, null,
                ResolutionStatus.RESOLVED, InformationNature.DERIVED, confidence, DERIVED_ORIGIN,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "derived path " + id,
                        ref(source), ref(target), null, confidence
                ))
        );
    }

    private static Relationship heuristic(
            String id,
            Symbol source,
            Symbol target,
            RelationshipKind kind,
            double confidence
    ) {
        return new Relationship(
                id, PROJECT_ID.toString(), ref(source), ref(target), null, kind, null,
                ResolutionStatus.HEURISTIC, InformationNature.HEURISTIC, confidence, DERIVED_ORIGIN,
                List.of(new Evidence(
                        EvidenceType.NAMING_CONVENTION,
                        "heuristic path " + id,
                        ref(source), ref(target), null, confidence
                ))
        );
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }
}
