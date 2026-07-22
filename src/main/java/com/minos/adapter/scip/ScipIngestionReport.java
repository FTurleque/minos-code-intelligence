package com.minos.adapter.scip;

/**
 * Mesures minimales produites par une ingestion SCIP M0.
 */
record ScipIngestionReport(
        int catalogSymbolCount,
        int normalizedSymbolCount,
        int skippedSymbolCount,
        int occurrenceCount,
        int resolvedOccurrenceCount,
        int unresolvedOccurrenceCount,
        int skippedOccurrenceCount) {

    double unresolvedOccurrenceRate() {
        int considered = resolvedOccurrenceCount + unresolvedOccurrenceCount;
        return considered == 0 ? 0.0 : (double) unresolvedOccurrenceCount / considered;
    }
}
