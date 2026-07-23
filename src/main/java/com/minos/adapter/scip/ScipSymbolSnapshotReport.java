package com.minos.adapter.scip;

/**
 * Résultat public de la publication d'un snapshot de connaissance SCIP.
 */
public record ScipSymbolSnapshotReport(
        String snapshotId,
        int catalogSymbolCount,
        int normalizedSymbolCount,
        int skippedSymbolCount,
        int occurrenceCount,
        int resolvedOccurrenceCount,
        int unresolvedOccurrenceCount,
        int skippedOccurrenceCount,
        int providerRelationshipCount,
        int providerRelationshipFactCount,
        int relationshipCount,
        int derivedRelationshipCount,
        int relatedTestRelationshipCount,
        int resolvedRelationshipCount,
        int unresolvedRelationshipCount,
        int skippedRelationshipFactCount,
        int duplicateRelationshipCount
) {
}
