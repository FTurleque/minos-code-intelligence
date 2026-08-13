package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.incremental.IncrementalIndexingPlan;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

final class IndexingLifecyclePlanSupport {
    private final IndexerExecutionScopeResolver scopes = new IndexerExecutionScopeResolver();

    void validate(UUID projectId, Path root, IndexerNegotiationResult negotiation) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(root, "projectRoot");
        Objects.requireNonNull(negotiation, "negotiation");
        if (!negotiation.complete()) throw new IllegalArgumentException("indexer negotiation must cover every detected language");
        if (negotiation.selections().isEmpty()) throw new IllegalArgumentException("indexer negotiation must contain at least one selection");
    }

    List<IndexingExecutionTarget> rootTargets(IndexerNegotiationResult negotiation) {
        return negotiation.selections().stream()
                .map(selection -> new IndexingExecutionTarget(selection, Path.of(""))).toList();
    }

    List<IndexingExecutionTarget> scopedTargets(Path projectRoot, ProjectDiscovery discovery,
                                                IndexerNegotiationResult negotiation) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!root.equals(Objects.requireNonNull(discovery, "discovery").rootPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("discovery belongs to another project root");
        }
        List<IndexingExecutionTarget> result = new ArrayList<>();
        for (IndexerSelection selection : negotiation.selections()) {
            for (Path relative : scopes.resolve(discovery, negotiation, selection)) {
                result.add(new IndexingExecutionTarget(selection, relative));
            }
        }
        return List.copyOf(result);
    }

    void validatePlan(IncrementalIndexingPlan plan, IndexerNegotiationResult negotiation) {
        TreeSet<String> ids = new TreeSet<>();
        for (IndexerSelection selection : negotiation.selections()) ids.add(selection.indexer().id());
        if (!List.copyOf(ids).equals(plan.selectedIndexerIds())) {
            throw new IllegalArgumentException("plan selections do not match indexer negotiation");
        }
        if (plan.mode() == IndexingMode.INCREMENTAL) {
            for (IndexerSelection selection : negotiation.selections()) {
                if (!selection.indexer().capabilities().contains(IndexerCapability.INCREMENTAL_INDEXING)) {
                    throw new IllegalArgumentException("incremental plan contains an indexer without INCREMENTAL_INDEXING: "
                            + selection.indexer().id());
                }
            }
        }
    }
}
