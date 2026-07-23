package com.minos.incremental;

import com.minos.orchestration.IndexingMode;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Plan fournisseur-indépendant expliquant si MINOS ne fait rien, exécute un
 * refresh complet ou peut demander une exécution incrémentale.
 */
public record IncrementalIndexingPlan(
        UUID projectId,
        IndexingMode mode,
        ProjectInvalidationAssessment invalidation,
        List<String> changedFiles,
        List<String> selectedIndexerIds,
        List<String> incrementalCapableIndexerIds,
        List<String> missingIncrementalCapabilityIndexerIds,
        List<IncrementalIndexingPlanReason> reasons
) {
    public IncrementalIndexingPlan {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(invalidation, "invalidation");
        if (!projectId.equals(invalidation.projectId())) {
            throw new IllegalArgumentException("plan and invalidation must belong to the same project");
        }
        changedFiles = immutableSortedUnique(changedFiles, "changedFiles");
        selectedIndexerIds = immutableSortedUnique(selectedIndexerIds, "selectedIndexerIds");
        incrementalCapableIndexerIds = immutableSortedUnique(
                incrementalCapableIndexerIds,
                "incrementalCapableIndexerIds"
        );
        missingIncrementalCapabilityIndexerIds = immutableSortedUnique(
                missingIncrementalCapabilityIndexerIds,
                "missingIncrementalCapabilityIndexerIds"
        );
        reasons = immutableSortedReasons(reasons);

        if (!selectedIndexerIds.containsAll(incrementalCapableIndexerIds)
                || !selectedIndexerIds.containsAll(missingIncrementalCapabilityIndexerIds)) {
            throw new IllegalArgumentException("capability lists must refer to selected indexers");
        }
        TreeSet<String> overlap = new TreeSet<>(incrementalCapableIndexerIds);
        overlap.retainAll(missingIncrementalCapabilityIndexerIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("an indexer cannot both support and miss incremental indexing");
        }

        switch (mode) {
            case NONE -> {
                if (invalidation.scope() != ProjectInvalidationScope.NONE
                        || !changedFiles.isEmpty()
                        || reasons.size() != 1
                        || reasons.getFirst() != IncrementalIndexingPlanReason.NO_CHANGES) {
                    throw new IllegalArgumentException("NONE requires an unchanged invalidation and NO_CHANGES reason");
                }
            }
            case INCREMENTAL -> {
                if (invalidation.scope() != ProjectInvalidationScope.PARTIAL_CANDIDATE
                        || changedFiles.isEmpty()
                        || selectedIndexerIds.isEmpty()
                        || !missingIncrementalCapabilityIndexerIds.isEmpty()
                        || !incrementalCapableIndexerIds.equals(selectedIndexerIds)
                        || !reasons.contains(IncrementalIndexingPlanReason.ALL_INDEXERS_SUPPORT_INCREMENTAL)) {
                    throw new IllegalArgumentException("INCREMENTAL requires a partial candidate supported by every indexer");
                }
            }
            case FULL -> {
                if (invalidation.scope() == ProjectInvalidationScope.NONE || selectedIndexerIds.isEmpty()) {
                    throw new IllegalArgumentException("FULL requires changes and at least one selected indexer");
                }
                if (invalidation.scope() == ProjectInvalidationScope.FULL_REQUIRED
                        && !reasons.contains(IncrementalIndexingPlanReason.INVALIDATION_REQUIRES_FULL)) {
                    throw new IllegalArgumentException("FULL_REQUIRED invalidation requires INVALIDATION_REQUIRES_FULL");
                }
                if (invalidation.scope() == ProjectInvalidationScope.PARTIAL_CANDIDATE
                        && (!reasons.contains(IncrementalIndexingPlanReason.INDEXER_INCREMENTAL_CAPABILITY_MISSING)
                        || missingIncrementalCapabilityIndexerIds.isEmpty())) {
                    throw new IllegalArgumentException("partial candidate fallback requires a missing capability reason");
                }
            }
        }
    }

    private static List<String> immutableSortedUnique(List<String> values, String label) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, label));
        String previous = null;
        for (String value : copy) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " must not contain blank values");
            }
            if (previous != null && previous.compareTo(value) >= 0) {
                throw new IllegalArgumentException(label + " must be strictly sorted and unique");
            }
            previous = value;
        }
        return copy;
    }

    private static List<IncrementalIndexingPlanReason> immutableSortedReasons(
            List<IncrementalIndexingPlanReason> values
    ) {
        List<IncrementalIndexingPlanReason> copy = List.copyOf(Objects.requireNonNull(values, "reasons"));
        for (int index = 0; index < copy.size(); index++) {
            Objects.requireNonNull(copy.get(index), "reasons element");
            if (index > 0 && copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException("reasons must be strictly sorted and unique");
            }
        }
        return copy;
    }
}
