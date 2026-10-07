package com.minos.query;

import com.minos.domain.RelationshipKind;
import com.minos.domain.SymbolOccurrence;
import com.minos.store.CodeKnowledgeSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a snapshot does not let the relationship-based answers (impact, architecture, callers, dependencies) see,
 * stated once for every output (MINOS-AUD-F01).
 *
 * <p>The rules read the <em>content</em> of the snapshot, never the name of the provider nor the host: a snapshot may
 * mix providers, and the rule must stay true when part of the occurrences is projected into relationships later.
 * A SCIP index stores most of its references as occurrences; they do not become source-to-target relationships, so a
 * traversal over relationships cannot see them. Saying so is the minimum an impact tool owes its reader: an empty
 * answer is not proof of an absent path.</p>
 */
public final class SnapshotCoverageLimitations {

    private static final String SNAPSHOT = "snapshot";

    /** References held as occurrences are not projected into relationships: traversals and aggregates cannot see them. */
    public static final String OCCURRENCE_REFERENCES_NOT_PROJECTED = "OCCURRENCE_REFERENCES_NOT_PROJECTED";
    /** The snapshot holds no call relation at all: a caller or callee query cannot answer from it. */
    public static final String CALL_RELATIONS_NOT_PRODUCED = "CALL_RELATIONS_NOT_PRODUCED";

    private SnapshotCoverageLimitations() {
    }

    /** At least one resolved occurrence that is not a definition (a forward definition is a definition). */
    public static boolean hasResolvedReferenceOccurrences(CodeKnowledgeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, SNAPSHOT);
        for (SymbolOccurrence occurrence : snapshot.occurrences()) {
            if (occurrence.isResolved() && !occurrence.isDefinitionOccurrence()) {
                return true;
            }
        }
        return false;
    }

    /** At least one relation of kind {@link RelationshipKind#CALLS}. */
    public static boolean hasCallRelations(CodeKnowledgeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, SNAPSHOT);
        return snapshot.relationships().stream().anyMatch(relationship -> relationship.kind() == RelationshipKind.CALLS);
    }

    /**
     * The limitations that apply to an answer built from relationships of the given kinds, in a stable order:
     * {@code CALLS} says that no call relation exists when none does; {@code DEPENDS_ON} (derived from relationships
     * only) says that occurrence references are not aggregated; {@code IMPLEMENTS} and {@code RELATED_TEST} are
     * complete as far as the provider states them and give nothing.
     */
    public static List<String> relationshipLimitations(CodeKnowledgeSnapshot snapshot, Set<RelationshipKind> kinds) {
        Objects.requireNonNull(snapshot, SNAPSHOT);
        Objects.requireNonNull(kinds, "kinds");
        List<String> limitations = new ArrayList<>();
        if (kinds.contains(RelationshipKind.CALLS) && !hasCallRelations(snapshot)) {
            limitations.add(CALL_RELATIONS_NOT_PRODUCED);
        }
        if (kinds.contains(RelationshipKind.DEPENDS_ON) && hasResolvedReferenceOccurrences(snapshot)) {
            limitations.add(OCCURRENCE_REFERENCES_NOT_PROJECTED);
        }
        return List.copyOf(limitations);
    }
}
