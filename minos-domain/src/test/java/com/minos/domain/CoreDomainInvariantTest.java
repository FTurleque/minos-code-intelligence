package com.minos.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreDomainInvariantTest {

    @Test
    void codeEntityReferenceRejectsMissingAndOversizedIdentity() {
        CodeEntityType type = CodeEntityType.values()[0];
        assertThrows(NullPointerException.class, () -> new CodeEntityRef(null, "id"));
        assertThrows(IllegalArgumentException.class, () -> new CodeEntityRef(type, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new CodeEntityRef(type, "x".repeat(CodeEntityRef.MAX_ID_UTF8_BYTES + 1)));
        assertEquals("id", new CodeEntityRef(type, "id").id());
    }

    @Test
    void originAndEvidenceEnforceRequiredProvenanceAndProbability() {
        OriginType sourceType = OriginType.values()[0];
        assertThrows(IllegalArgumentException.class, () -> new Origin(" ", null, null, null, sourceType));
        assertThrows(NullPointerException.class, () -> new Origin("provider", null, null, null, null));

        Origin origin = new Origin("provider", "scip", "1", "run", sourceType);
        assertEquals("provider", origin.providerId());

        EvidenceType evidenceType = EvidenceType.values()[0];
        assertThrows(NullPointerException.class, () -> new Evidence(null, "evidence", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Evidence(evidenceType, " ", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Evidence(evidenceType, "evidence", null, null, null, 1.01));
        assertEquals(0.5, new Evidence(evidenceType, "evidence", null, null, null, 0.5).weight());
    }

    @Test
    void symbolNormalizesProviderReferencesAndRejectsRequiredText() {
        Origin origin = origin();
        Symbol symbol = new Symbol(
                "symbol-id",
                "symbol-key",
                SymbolIdentityQuality.values()[0],
                "project",
                null,
                "file",
                null,
                SymbolKind.values()[0],
                "SymbolName",
                "example.SymbolName",
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                origin,
                false,
                false,
                null
        );

        assertTrue(symbol.providerReferences().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Symbol(
                " ", "symbol-key", SymbolIdentityQuality.values()[0], "project", null, "file", null,
                SymbolKind.values()[0], "name", null, null, "java", null, ResolutionStatus.RESOLVED,
                origin, false, false, null));
        assertThrows(NullPointerException.class, () -> new Symbol(
                "id", "symbol-key", null, "project", null, "file", null,
                SymbolKind.values()[0], "name", null, null, "java", null, ResolutionStatus.RESOLVED,
                origin, false, false, null));
    }

    @Test
    void relationshipEnforcesResolvedAndUnresolvedTargetConsistency() {
        CodeEntityRef source = ref("source");
        CodeEntityRef target = ref("target");
        Origin origin = origin();
        RelationshipKind kind = RelationshipKind.values()[0];

        Relationship resolved = new Relationship(
                "rel", "project", source, target, null, kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, origin, null);
        assertTrue(resolved.evidence().isEmpty());

        Relationship unresolved = new Relationship(
                "rel-unresolved", "project", source, null, "missing.Target", kind, null,
                ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, origin, List.of());
        assertEquals("missing.Target", unresolved.unresolvedTarget());

        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "bad", "project", source, null, null, kind, null,
                ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, origin, null));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "bad", "project", source, target, "also-unresolved", kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, origin, null));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "bad", "project", source, null, "missing", kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, origin, null));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "bad", "project", source, target, null, kind, null,
                ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, origin, null));
    }

    @Test
    void nonFactualRelationshipRequiresConfidenceAndEvidence() {
        InformationNature nonFactual = Arrays.stream(InformationNature.values())
                .filter(value -> value != InformationNature.FACTUAL)
                .findFirst()
                .orElseThrow();
        CodeEntityRef source = ref("source");
        CodeEntityRef target = ref("target");
        Evidence evidence = new Evidence(EvidenceType.values()[0], "derived evidence", source, target, null, 1.0);

        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "missing-confidence", "project", source, target, null, RelationshipKind.values()[0], null,
                ResolutionStatus.RESOLVED, nonFactual, null, origin(), List.of(evidence)));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(
                "missing-evidence", "project", source, target, null, RelationshipKind.values()[0], null,
                ResolutionStatus.RESOLVED, nonFactual, 0.8, origin(), List.of()));

        Relationship relationship = new Relationship(
                "derived", "project", source, target, null, RelationshipKind.values()[0], null,
                ResolutionStatus.RESOLVED, nonFactual, 0.8, origin(), List.of(evidence));
        assertEquals(0.8, relationship.confidence());
        assertNotNull(relationship.evidence().getFirst());
    }

    private static CodeEntityRef ref(String id) {
        return new CodeEntityRef(CodeEntityType.values()[0], id);
    }

    private static Origin origin() {
        return new Origin("provider", "scip", "1", "run", OriginType.values()[0]);
    }
}
