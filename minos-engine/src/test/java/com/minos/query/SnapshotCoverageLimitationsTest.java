package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-F01 : les deux règles de limitation sont calculées sur le contenu du snapshot, jamais sur le nom du
 * fournisseur ni sur l'hôte, et partagées par l'impact, l'architecture et les requêtes de relations.
 */
class SnapshotCoverageLimitationsTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000f01");
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void aResolvedNonDefinitionOccurrenceIsAnOccurrenceReferenceThatIsNotProjected() {
        for (OccurrenceRole role : EnumSet.complementOf(EnumSet.of(OccurrenceRole.DEFINITION, OccurrenceRole.FORWARD_DEFINITION))) {
            assertTrue(SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(
                    snapshot(List.of(), List.of(occurrence("o", true, role)))), "role " + role);
        }
    }

    @Test
    void definitionsAndUnresolvedOccurrencesAreNotReferences() {
        assertFalse(SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(snapshot(List.of(), List.of())));
        assertFalse(SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(
                snapshot(List.of(), List.of(occurrence("d", true, OccurrenceRole.DEFINITION)))));
        assertFalse(SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(
                snapshot(List.of(), List.of(occurrence("f", true, OccurrenceRole.FORWARD_DEFINITION)))),
                "a forward definition is a definition");
        assertFalse(SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(
                snapshot(List.of(), List.of(occurrence("u", false, OccurrenceRole.REFERENCE)))),
                "an unresolved occurrence is ignored");
    }

    @Test
    void aDefinitionThatIsAlsoAReferenceRoleIsStillADefinition() {
        SymbolOccurrence both = new SymbolOccurrence("o", PROJECT.toString(), new ResolvedSymbolReference("s"),
                location(), Set.of(OccurrenceRole.DEFINITION, OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED,
                ORIGIN, Set.of());

        assertFalse(SnapshotCoverageLimitations.hasResolvedReferenceOccurrences(snapshot(List.of(), List.of(both))));
    }

    @Test
    void callRelationsAreOnlyThoseOfKindCalls() {
        Symbol a = symbol("a");
        Symbol b = symbol("b");
        assertFalse(SnapshotCoverageLimitations.hasCallRelations(snapshot(
                List.of(relationship("r1", a, b, RelationshipKind.REFERENCES)), List.of())));
        assertTrue(SnapshotCoverageLimitations.hasCallRelations(snapshot(
                List.of(relationship("r2", a, b, RelationshipKind.CALLS)), List.of())));
    }

    @Test
    void relationshipLimitationsDependOnTheKindsAskedFor() {
        CodeKnowledgeSnapshot withReferences = snapshot(List.of(), List.of(occurrence("o", true, OccurrenceRole.REFERENCE)));

        assertEquals(List.of(SnapshotCoverageLimitations.CALL_RELATIONS_NOT_PRODUCED),
                SnapshotCoverageLimitations.relationshipLimitations(withReferences, Set.of(RelationshipKind.CALLS)));
        assertEquals(List.of(SnapshotCoverageLimitations.OCCURRENCE_REFERENCES_NOT_PROJECTED),
                SnapshotCoverageLimitations.relationshipLimitations(withReferences, Set.of(RelationshipKind.DEPENDS_ON)));
        assertEquals(List.of(), SnapshotCoverageLimitations.relationshipLimitations(
                withReferences, Set.of(RelationshipKind.IMPLEMENTS)));
        assertEquals(List.of(), SnapshotCoverageLimitations.relationshipLimitations(
                withReferences, Set.of(RelationshipKind.RELATED_TEST)));
    }

    @Test
    void callLimitationIsAbsentWhenTheSnapshotHoldsACall() {
        Symbol a = symbol("a");
        Symbol b = symbol("b");
        CodeKnowledgeSnapshot withCall = snapshot(List.of(relationship("r", a, b, RelationshipKind.CALLS)), List.of());

        assertEquals(List.of(), SnapshotCoverageLimitations.relationshipLimitations(withCall, Set.of(RelationshipKind.CALLS)));
    }

    @Test
    void dependsOnLimitationIsAbsentWithoutResolvedReferences() {
        assertEquals(List.of(), SnapshotCoverageLimitations.relationshipLimitations(
                snapshot(List.of(), List.of()), Set.of(RelationshipKind.DEPENDS_ON)));
    }

    private static CodeKnowledgeSnapshot snapshot(List<Relationship> relationships, List<SymbolOccurrence> occurrences) {
        List<Symbol> symbols = List.of(symbol("s"), symbol("a"), symbol("b"));
        return new CodeKnowledgeSnapshot(PROJECT, "snapshot-f01", symbols, occurrences, relationships);
    }

    private static SymbolOccurrence occurrence(String id, boolean resolved, OccurrenceRole role) {
        return new SymbolOccurrence(id, PROJECT.toString(),
                resolved ? new ResolvedSymbolReference("s")
                        : new UnresolvedSymbolReference("Missing", null, "java", "not found", Set.of()),
                location(), Set.of(role), resolved ? ResolutionStatus.RESOLVED : ResolutionStatus.UNRESOLVED, ORIGIN,
                Set.of());
    }

    private static SymbolLocation location() {
        return new SymbolLocation("src/Main.java", 1, 0, 1, 4, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Symbol symbol(String id) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, PROJECT.toString(), null,
                "src/" + id + ".java", null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }

    private static Relationship relationship(String id, Symbol source, Symbol target, RelationshipKind kind) {
        return new Relationship(id, PROJECT.toString(), ref(source), ref(target), null, kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of());
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }
}
