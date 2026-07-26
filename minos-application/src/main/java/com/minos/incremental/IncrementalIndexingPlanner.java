package com.minos.incremental;

import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexingMode;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Combine l'invalidation M7.3 avec les capacités des indexeurs sélectionnés.
 *
 * <p>L'atomicité reste projet : si un seul indexeur sélectionné n'a pas une
 * capacité incrémentale explicitement qualifiée, tout le refresh retombe en
 * {@link IndexingMode#FULL}.</p>
 */
public final class IncrementalIndexingPlanner {

    public IncrementalIndexingPlan plan(
            ProjectInvalidationAssessment invalidation,
            IndexerNegotiationResult negotiation
    ) {
        Objects.requireNonNull(invalidation, "invalidation");
        Objects.requireNonNull(negotiation, "negotiation");
        if (!negotiation.complete()) {
            throw new IllegalArgumentException("indexer negotiation must cover every detected language");
        }

        TreeSet<String> selected = new TreeSet<>();
        TreeSet<String> capable = new TreeSet<>();
        TreeSet<String> missing = new TreeSet<>();
        for (IndexerNegotiationResult.IndexerSelection selection : negotiation.selections()) {
            String indexerId = selection.indexer().id();
            if (!selected.add(indexerId)) {
                throw new IllegalArgumentException("selected indexer ids must be unique: " + indexerId);
            }
            if (selection.indexer().capabilities().contains(IndexerCapability.INCREMENTAL_INDEXING)) {
                capable.add(indexerId);
            } else {
                missing.add(indexerId);
            }
        }

        if (invalidation.scope() == ProjectInvalidationScope.NONE) {
            return new IncrementalIndexingPlan(
                    invalidation.projectId(),
                    IndexingMode.NONE,
                    invalidation,
                    List.of(),
                    List.copyOf(selected),
                    List.copyOf(capable),
                    List.copyOf(missing),
                    List.of(IncrementalIndexingPlanReason.NO_CHANGES)
            );
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("changed project requires at least one selected indexer");
        }

        List<String> changedFiles = changedFiles(invalidation);
        if (invalidation.scope() == ProjectInvalidationScope.FULL_REQUIRED) {
            return new IncrementalIndexingPlan(
                    invalidation.projectId(),
                    IndexingMode.FULL,
                    invalidation,
                    changedFiles,
                    List.copyOf(selected),
                    List.copyOf(capable),
                    List.copyOf(missing),
                    List.of(IncrementalIndexingPlanReason.INVALIDATION_REQUIRES_FULL)
            );
        }

        if (missing.isEmpty()) {
            return new IncrementalIndexingPlan(
                    invalidation.projectId(),
                    IndexingMode.INCREMENTAL,
                    invalidation,
                    changedFiles,
                    List.copyOf(selected),
                    List.copyOf(capable),
                    List.of(),
                    List.of(IncrementalIndexingPlanReason.ALL_INDEXERS_SUPPORT_INCREMENTAL)
            );
        }

        return new IncrementalIndexingPlan(
                invalidation.projectId(),
                IndexingMode.FULL,
                invalidation,
                changedFiles,
                List.copyOf(selected),
                List.copyOf(capable),
                List.copyOf(missing),
                List.of(IncrementalIndexingPlanReason.INDEXER_INCREMENTAL_CAPABILITY_MISSING)
        );
    }

    private static List<String> changedFiles(ProjectInvalidationAssessment invalidation) {
        TreeSet<String> files = new TreeSet<>();
        invalidation.changeSet().ifPresent(changeSet -> {
            files.addAll(changeSet.addedFiles());
            files.addAll(changeSet.modifiedFiles());
            files.addAll(changeSet.deletedFiles());
        });
        return List.copyOf(files);
    }
}
