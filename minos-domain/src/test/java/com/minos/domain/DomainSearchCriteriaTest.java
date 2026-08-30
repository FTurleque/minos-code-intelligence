package com.minos.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainSearchCriteriaTest {

    @Test
    void symbolCriteriaNormalizesBlanksAndProvidesFactories() {
        SymbolSearchCriteria lexical = SymbolSearchCriteria.lexical("needle", 25);
        assertEquals("needle", lexical.text());
        assertNull(lexical.qualifiedName());
        assertEquals(25, lexical.limit());

        SymbolSearchCriteria qualified = SymbolSearchCriteria.qualifiedName("a.b.Type", 10);
        assertEquals("a.b.Type", qualified.qualifiedName());
        assertNull(qualified.text());

        SymbolSearchCriteria byKind = new SymbolSearchCriteria(" ", null, SymbolKind.values()[0], " ", 5);
        assertNull(byKind.text());
        assertNull(byKind.moduleId());
        assertEquals(SymbolKind.values()[0], byKind.kind());
    }

    @Test
    void symbolCriteriaRejectsEmptyInvalidLimitAndOversizedText() {
        assertThrows(IllegalArgumentException.class,
                () -> new SymbolSearchCriteria(" ", "", null, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SymbolSearchCriteria.lexical("needle", 0));
        String oversizedText = "x".repeat(SymbolSearchCriteria.MAX_TEXT_UTF8_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> SymbolSearchCriteria.lexical(oversizedText, 1));
    }

    @Test
    void relationshipCriteriaFactoriesSetDirectionAndDefensivelyCopyKinds() {
        CodeEntityRef anchor = new CodeEntityRef(CodeEntityType.values()[0], "anchor");
        RelationshipKind kind = RelationshipKind.values()[0];
        HashSet<RelationshipKind> mutableKinds = new HashSet<>(Set.of(kind));

        RelationshipSearchCriteria outgoing = RelationshipSearchCriteria.outgoing(anchor, mutableKinds, 20);
        mutableKinds.clear();
        RelationshipSearchCriteria incoming = RelationshipSearchCriteria.incoming(anchor, null, 20);
        RelationshipSearchCriteria any = RelationshipSearchCriteria.any(anchor, Set.of(), 20);

        assertEquals(RelationshipDirection.OUTGOING, outgoing.direction());
        Set<RelationshipKind> outgoingKinds = outgoing.kinds();
        assertEquals(Set.of(kind), outgoingKinds);
        assertThrows(UnsupportedOperationException.class, () -> outgoingKinds.clear());
        assertEquals(RelationshipDirection.INCOMING, incoming.direction());
        assertTrue(incoming.kinds().isEmpty());
        assertEquals(RelationshipDirection.ANY, any.direction());
        assertTrue(any.kinds().isEmpty());
    }

    @Test
    void relationshipCriteriaRejectsMissingAnchorDirectionAndInvalidLimit() {
        CodeEntityRef anchor = new CodeEntityRef(CodeEntityType.values()[0], "anchor");
        assertThrows(NullPointerException.class,
                () -> new RelationshipSearchCriteria(null, RelationshipDirection.ANY, null, null, null, 1));
        assertThrows(NullPointerException.class,
                () -> new RelationshipSearchCriteria(anchor, null, null, null, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RelationshipSearchCriteria(anchor, RelationshipDirection.ANY, null, null, null, 0));
    }
}
