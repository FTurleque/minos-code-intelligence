package com.minos.adapter.scip;

/**
 * Mesures produites par une ingestion SCIP normalisée.
 */
record ScipIngestionReport(
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
        int duplicateRelationshipCount) {

    double unresolvedOccurrenceRate() {
        int considered = resolvedOccurrenceCount + unresolvedOccurrenceCount;
        return considered == 0 ? 0.0 : (double) unresolvedOccurrenceCount / considered;
    }
}
