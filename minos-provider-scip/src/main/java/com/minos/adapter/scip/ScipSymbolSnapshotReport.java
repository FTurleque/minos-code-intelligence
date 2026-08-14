package com.minos.adapter.scip;

import java.util.Objects;

/** Résultat public de la publication d'un snapshot de connaissance SCIP. */
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
        int duplicateRelationshipCount,
        CommitStatus commitStatus,
        String commitDiagnostic
) {
    public enum CommitStatus {
        COMMITTED,
        COMMITTED_DURABILITY_PENDING
    }

    public ScipSymbolSnapshotReport {
        commitStatus = Objects.requireNonNull(commitStatus, "commitStatus");
        commitDiagnostic = commitDiagnostic == null || commitDiagnostic.isBlank() ? null : commitDiagnostic;
    }

    /** Compatibility constructor for callers that do not need commit diagnostics. */
    public ScipSymbolSnapshotReport(
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
        this(snapshotId, catalogSymbolCount, normalizedSymbolCount, skippedSymbolCount, occurrenceCount,
                resolvedOccurrenceCount, unresolvedOccurrenceCount, skippedOccurrenceCount,
                providerRelationshipCount, providerRelationshipFactCount, relationshipCount,
                derivedRelationshipCount, relatedTestRelationshipCount, resolvedRelationshipCount,
                unresolvedRelationshipCount, skippedRelationshipFactCount, duplicateRelationshipCount,
                CommitStatus.COMMITTED, null);
    }
}
