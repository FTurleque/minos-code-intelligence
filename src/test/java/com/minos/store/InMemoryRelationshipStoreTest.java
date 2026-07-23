package com.minos.store;

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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryRelationshipStoreTest {

    private static final String PROJECT_ID = "project-1";

    @Test
    void relationshipIdsAreScopedByProject() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef source = symbol("service");
        store.putRelationships(List.of(
                resolved("shared-id", PROJECT_ID, source, symbol("api"), RelationshipKind.IMPLEMENTS),
                resolved("shared-id", "project-2", source, symbol("other-api"), RelationshipKind.IMPLEMENTS)
        ));

        List<Relationship> firstProject = store.findRelationships(
                PROJECT_ID,
                RelationshipSearchCriteria.outgoing(source, Set.of(), 10)
        );
        List<Relationship> secondProject = store.findRelationships(
                "project-2",
                RelationshipSearchCriteria.outgoing(source, Set.of(), 10)
        );

        assertEquals(List.of("api"), firstProject.stream().map(r -> r.target().id()).toList());
        assertEquals(List.of("other-api"), secondProject.stream().map(r -> r.target().id()).toList());
    }

    @Test
    void directionIsExplicitAndAnyReturnsOutgoingBeforeIncoming() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef service = symbol("service");
        Relationship outgoingImplementation = resolved(
                "out-implementation", PROJECT_ID, service, symbol("api"), RelationshipKind.IMPLEMENTS);
        Relationship outgoingUnresolved = unresolved(
                "out-unresolved", PROJECT_ID, service, "missing.Client", RelationshipKind.CALLS);
        Relationship incomingReference = resolved(
                "in-reference", PROJECT_ID, symbol("resource"), service, RelationshipKind.REFERENCES);
        store.putRelationships(List.of(incomingReference, outgoingUnresolved, outgoingImplementation));

        assertEquals(
                List.of("out-implementation", "out-unresolved"),
                ids(store.findRelationships(
                        PROJECT_ID,
                        RelationshipSearchCriteria.outgoing(service, Set.of(), 10)
                ))
        );
        assertEquals(
                List.of("in-reference"),
                ids(store.findRelationships(
                        PROJECT_ID,
                        RelationshipSearchCriteria.incoming(service, Set.of(), 10)
                ))
        );
        assertEquals(
                List.of("out-implementation", "out-unresolved", "in-reference"),
                ids(store.findRelationships(
                        PROJECT_ID,
                        new RelationshipSearchCriteria(
                                service,
                                RelationshipDirection.ANY,
                                Set.of(),
                                null,
                                null,
                                10
                        )
                ))
        );
    }

    @Test
    void filtersResolutionNatureAndKindBeforeApplyingLimit() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef service = symbol("service");
        store.putRelationships(List.of(
                derived("derived-a", service, symbol("repository-a"), RelationshipKind.DEPENDS_ON, 0.90),
                derived("derived-b", service, symbol("repository-b"), RelationshipKind.DEPENDS_ON, 0.75),
                resolved("factual", PROJECT_ID, service, symbol("repository-c"), RelationshipKind.DEPENDS_ON),
                unresolved("unresolved", PROJECT_ID, service, "missing.Repository", RelationshipKind.DEPENDS_ON),
                resolved("other-kind", PROJECT_ID, service, symbol("api"), RelationshipKind.IMPLEMENTS)
        ));

        RelationshipSearchCriteria criteria = new RelationshipSearchCriteria(
                service,
                RelationshipDirection.OUTGOING,
                Set.of(RelationshipKind.DEPENDS_ON),
                ResolutionStatus.RESOLVED,
                InformationNature.DERIVED,
                1
        );

        assertEquals(List.of("derived-a"), ids(store.findRelationships(PROJECT_ID, criteria)));
    }

    @Test
    void resultsAreDeterministicAndImmutable() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef service = symbol("service");
        store.putRelationships(List.of(
                resolved("z-last", PROJECT_ID, service, symbol("z-target"), RelationshipKind.REFERENCES),
                resolved("a-second", PROJECT_ID, service, symbol("a-target"), RelationshipKind.REFERENCES),
                unresolved("missing", PROJECT_ID, service, "missing.Target", RelationshipKind.REFERENCES),
                resolved("implementation", PROJECT_ID, service, symbol("api"), RelationshipKind.IMPLEMENTS)
        ));

        List<Relationship> result = store.findRelationships(
                PROJECT_ID,
                RelationshipSearchCriteria.outgoing(service, Set.of(), 10)
        );

        assertEquals(
                List.of("a-second", "z-last", "missing", "implementation"),
                ids(result)
        );
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.getFirst()));
    }

    @Test
    void rejectsInvalidProjectCriteriaAndLimit() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        CodeEntityRef service = symbol("service");

        assertThrows(IllegalArgumentException.class, () -> store.findRelationships(" ",
                RelationshipSearchCriteria.outgoing(service, Set.of(), 10)));
        assertThrows(IllegalArgumentException.class, () -> store.findRelationships(PROJECT_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new RelationshipSearchCriteria(
                service, RelationshipDirection.OUTGOING, Set.of(), null, null, 0));
    }

    private static List<String> ids(List<Relationship> relationships) {
        return relationships.stream().map(Relationship::id).toList();
    }

    private static Relationship resolved(
            String id,
            String projectId,
            CodeEntityRef source,
            CodeEntityRef target,
            RelationshipKind kind
    ) {
        return new Relationship(
                id,
                projectId,
                source,
                target,
                null,
                kind,
                location(id, 10),
                ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL,
                null,
                origin(),
                List.of(evidence(source, target, id))
        );
    }

    private static Relationship unresolved(
            String id,
            String projectId,
            CodeEntityRef source,
            String targetName,
            RelationshipKind kind
    ) {
        return new Relationship(
                id,
                projectId,
                source,
                null,
                targetName,
                kind,
                location(id, 20),
                ResolutionStatus.UNRESOLVED,
                InformationNature.FACTUAL,
                null,
                origin(),
                List.of(evidence(source, null, id))
        );
    }

    private static Relationship derived(
            String id,
            CodeEntityRef source,
            CodeEntityRef target,
            RelationshipKind kind,
            double confidence
    ) {
        return new Relationship(
                id,
                PROJECT_ID,
                source,
                target,
                null,
                kind,
                location(id, 30),
                ResolutionStatus.RESOLVED,
                InformationNature.DERIVED,
                confidence,
                derivedOrigin(),
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "derived from fixture path " + id,
                        source,
                        target,
                        location(id, 30),
                        confidence
                ))
        );
    }

    private static Evidence evidence(CodeEntityRef source, CodeEntityRef target, String id) {
        return new Evidence(
                EvidenceType.PROVIDER_FACT,
                "provider fact " + id,
                source,
                target,
                location(id, 10),
                1.0
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
