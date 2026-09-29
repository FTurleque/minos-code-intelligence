package com.minos.query;

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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DependencyDerivationServiceTest {

    private final DependencyDerivationService service = new DependencyDerivationService();

    @Test
    void coalescesDependencyFactsAndPreservesStructuredProofsDeterministically() {
        Relationship reference = factual(
                "reference", symbol("service"), symbol("port"), null,
                RelationshipKind.REFERENCES, ResolutionStatus.RESOLVED);
        Relationship implementation = factual(
                "implementation", symbol("service"), symbol("port"), null,
                RelationshipKind.IMPLEMENTS, ResolutionStatus.RESOLVED);

        Relationship first = service.derive(List.of(reference, implementation)).getFirst();
        Relationship reversed = service.derive(List.of(implementation, reference)).getFirst();

        assertEquals(first, reversed);
        assertEquals(RelationshipKind.DEPENDS_ON, first.kind());
        assertEquals(InformationNature.DERIVED, first.nature());
        assertEquals(1.0, first.confidence());
        assertEquals(OriginType.DERIVED_BY_MINOS, first.origin().sourceType());
        assertEquals(List.of(
                "Direct REFERENCES fact implies a code dependency",
                "Direct IMPLEMENTS fact implies a code dependency"
        ), first.evidence().stream().map(Evidence::description).toList());
    }

    @Test
    void preservesUnresolvedTargetAndSkipsNavigationOnlySelfAndDerivedFacts() {
        Relationship unresolved = factual(
                "unresolved", symbol("service"), null, "missing.Port",
                RelationshipKind.REFERENCES, ResolutionStatus.UNRESOLVED);
        Relationship definition = factual(
                "definition", symbol("service"), symbol("port"), null,
                RelationshipKind.DEFINITION, ResolutionStatus.RESOLVED);
        Relationship self = factual(
                "self", symbol("service"), symbol("service"), null,
                RelationshipKind.CALLS, ResolutionStatus.RESOLVED);
        Relationship alreadyDerived = derivedDependency();

        List<Relationship> derived = service.derive(List.of(
                definition, self, alreadyDerived, unresolved));

        assertEquals(1, derived.size());
        assertEquals("missing.Port", derived.getFirst().unresolvedTarget());
        assertNull(derived.getFirst().target());
        assertEquals(ResolutionStatus.UNRESOLVED, derived.getFirst().resolutionStatus());
    }

    private static Relationship factual(
            String id,
            CodeEntityRef source,
            CodeEntityRef target,
            String unresolvedTarget,
            RelationshipKind kind,
            ResolutionStatus resolutionStatus
    ) {
        return new Relationship(
                id, "project-1", source, target, unresolvedTarget, kind, null,
                resolutionStatus, InformationNature.FACTUAL, null, origin(), List.of()
        );
    }

    private static Relationship derivedDependency() {
        CodeEntityRef source = symbol("already-source");
        CodeEntityRef target = symbol("already-target");
        return new Relationship(
                "already-derived", "project-1", source, target, null,
                RelationshipKind.DEPENDS_ON, null, ResolutionStatus.RESOLVED,
                InformationNature.DERIVED, 1.0,
                new Origin("minos", "RELATIONSHIP_DERIVATION", "M3", "run-1",
                        OriginType.DERIVED_BY_MINOS),
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH, "already derived", source, target, null, 1.0))
        );
    }

    private static CodeEntityRef symbol(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static Origin origin() {
        return new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    }
}
