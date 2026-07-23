package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolLocation;
import com.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelationshipQueryServiceTest {

    private static final String PROJECT_ID = "project-1";

    @Test
    void findImplementationsReturnsOnlyIncomingImplementationRelations() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef api = symbol("greeting-port");
        store.putRelationships(List.of(
                factual("implementation-b", symbol("implementation-b"), api, RelationshipKind.IMPLEMENTS),
                factual("reference", symbol("resource"), api, RelationshipKind.REFERENCES),
                factual("implementation-a", symbol("implementation-a"), api, RelationshipKind.IMPLEMENTS),
                factual("outgoing", api, symbol("parent-port"), RelationshipKind.IMPLEMENTS)
        ));

        RelationshipQueryService service = new RelationshipQueryService(store);

        assertEquals(
                List.of("implementation-a", "implementation-b"),
                service.findImplementations(PROJECT_ID, api.id(), 10).stream()
                        .map(result -> result.source().id())
                        .toList()
        );
    }

    @Test
    void compactResultsPreserveResolutionProvenanceConfidenceAndEvidence() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        Relationship relationship = derivedDependency();
        store.putRelationships(List.of(relationship));

        RelationshipQueryService service = new RelationshipQueryService(store);
        List<RelationshipResult> results = service.findOutgoing(
                PROJECT_ID,
                relationship.source(),
                Set.of(RelationshipKind.DEPENDS_ON),
                10
        );

        RelationshipResult result = results.getFirst();
        assertEquals("derived-dependency", result.id());
        assertEquals(PROJECT_ID, result.projectId());
        assertEquals(ResolutionStatus.RESOLVED, result.resolutionStatus());
        assertEquals(InformationNature.DERIVED, result.nature());
        assertEquals(0.82, result.confidence());
        assertEquals(OriginType.DERIVED_BY_MINOS, result.origin().sourceType());
        assertEquals(EvidenceType.DERIVATION_PATH, result.evidence().getFirst().type());
        assertThrows(UnsupportedOperationException.class, () -> results.add(result));
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().add(result.evidence().getFirst()));
    }

    @Test
    void exposesDirectionalCallerCalleeDependencyAndDependentViews() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef caller = symbol("caller");
        CodeEntityRef callee = symbol("callee");
        CodeEntityRef dependency = symbol("dependency");
        store.putRelationships(List.of(
                factual("call", caller, callee, RelationshipKind.CALLS),
                derivedDependency("dependency", caller, dependency)
        ));

        RelationshipQueryService service = new RelationshipQueryService(store);

        assertEquals(List.of("caller"), service.findCallers(PROJECT_ID, callee.id(), 10)
                .stream().map(result -> result.source().id()).toList());
        assertEquals(List.of("callee"), service.findCallees(PROJECT_ID, caller.id(), 10)
                .stream().map(result -> result.target().id()).toList());
        assertEquals(List.of("dependency"), service.findDependencies(PROJECT_ID, caller, 10)
                .stream().map(result -> result.target().id()).toList());
        assertEquals(List.of("caller"), service.findDependents(PROJECT_ID, dependency, 10)
                .stream().map(result -> result.source().id()).toList());
    }

    @Test
    void exposesRelatedTestsAsIncomingRelationsOnProductionSymbol() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef test = symbol("service-test");
        CodeEntityRef production = symbol("service");
        store.putRelationships(List.of(new Relationship(
                "related-test", PROJECT_ID, test, production, null,
                RelationshipKind.RELATED_TEST, location("service-test", 1),
                ResolutionStatus.HEURISTIC, InformationNature.HEURISTIC, 0.7,
                derivedOrigin(), List.of(new Evidence(
                        EvidenceType.NAMING_CONVENTION, "ServiceTest matches Service",
                        test, production, location("service-test", 1), 0.55))
        )));

        var related = new RelationshipQueryService(store).findRelatedTests(
                PROJECT_ID, production.id(), 10);

        assertEquals(List.of("service-test"), related.stream()
                .map(result -> result.source().id()).toList());
    }

    @Test
    void searchCriteriaDefensivelyCopiesKinds() {
        EnumSet<RelationshipKind> kinds = EnumSet.of(RelationshipKind.CALLS);
        RelationshipSearchCriteria criteria = new RelationshipSearchCriteria(
                symbol("service"),
                RelationshipDirection.OUTGOING,
                kinds,
                null,
                null,
                10
        );

        kinds.add(RelationshipKind.REFERENCES);

        assertEquals(Set.of(RelationshipKind.CALLS), criteria.kinds());
        assertThrows(UnsupportedOperationException.class,
                () -> criteria.kinds().add(RelationshipKind.IMPLEMENTS));
    }

    @Test
    void relationshipRejectsInconsistentResolutionAndUnprovenDerivation() {
        CodeEntityRef source = symbol("source");
        CodeEntityRef target = symbol("target");

        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "unresolved-as-resolved", PROJECT_ID, source, null, "missing.Target",
                RelationshipKind.REFERENCES, null, ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL, null, origin(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "resolved-as-unresolved", PROJECT_ID, source, target, null,
                RelationshipKind.REFERENCES, null, ResolutionStatus.UNRESOLVED,
                InformationNature.FACTUAL, null, origin(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "derived-without-proof", PROJECT_ID, source, target, null,
                RelationshipKind.DEPENDS_ON, null, ResolutionStatus.RESOLVED,
                InformationNature.DERIVED, 0.5, derivedOrigin(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "derived-without-confidence", PROJECT_ID, source, target, null,
                RelationshipKind.DEPENDS_ON, null, ResolutionStatus.RESOLVED,
                InformationNature.DERIVED, null, derivedOrigin(), List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "derived path",
                        source,
                        target,
                        null,
                        0.5
                ))
        ));
    }

    private static Relationship factual(
            String id,
            CodeEntityRef source,
            CodeEntityRef target,
            RelationshipKind kind
    ) {
        SymbolLocation location = location(id, 10);
        return new Relationship(
                id,
                PROJECT_ID,
                source,
                target,
                null,
                kind,
                location,
                ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL,
                null,
                origin(),
                List.of(new Evidence(
                        EvidenceType.PROVIDER_FACT,
                        "provider fact " + id,
                        source,
                        target,
                        location,
                        1.0
                ))
        );
    }

    private static Relationship derivedDependency() {
        CodeEntityRef source = symbol("greeting-service");
        CodeEntityRef target = symbol("user-repository");
        return derivedDependency("derived-dependency", source, target);
    }

    private static Relationship derivedDependency(
            String id,
            CodeEntityRef source,
            CodeEntityRef target
    ) {
        SymbolLocation location = location("derived-dependency", 42);
        return new Relationship(
                id,
                PROJECT_ID,
                source,
                target,
                null,
                RelationshipKind.DEPENDS_ON,
                location,
                ResolutionStatus.RESOLVED,
                InformationNature.DERIVED,
                0.82,
                derivedOrigin(),
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "service references repository through constructor",
                        source,
                        target,
                        location,
                        0.82
                ))
        );
    }

    private static CodeEntityRef symbol(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static SymbolLocation location(String id, int line) {
        return new SymbolLocation(
                "src/" + id + ".java",
                line,
                0,
                line,
                10,
                PositionEncoding.UTF16_CODE_UNITS
        );
    }

    private static Origin origin() {
        return new Origin("fixture-provider", "TEST_FIXTURE", "1", "run-1", OriginType.OTHER);
    }

    private static Origin derivedOrigin() {
        return new Origin("minos", "MINOS", "1", "run-1", OriginType.DERIVED_BY_MINOS);
    }
}
