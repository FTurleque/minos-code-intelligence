package com.minos.impact;

import com.minos.query.SnapshotCoverageLimitations;

/**
 * Limites explicites de couverture de l'analyse d'impact.
 */
public enum ImpactLimitation {
    UNRESOLVED_RELATIONSHIPS_IGNORED,
    EXTERNAL_TARGETS_NOT_TRAVERSED,
    GENERATED_SYMBOLS_NOT_TRAVERSED,
    DYNAMIC_DISPATCH_NOT_PROVEN,
    REFLECTION_NOT_PROVEN,
    RUNTIME_CONFIGURATION_NOT_PROVEN,
    /**
     * Resolved references held as occurrences are not projected into relationships, so the traversal cannot see
     * them: an empty or short answer is not proof of an absent path (see {@link SnapshotCoverageLimitations}).
     */
    OCCURRENCE_REFERENCES_NOT_PROJECTED,
    MAX_DEPTH_REACHED,
    MAX_RESULTS_REACHED
}
