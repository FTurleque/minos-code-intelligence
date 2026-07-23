package com.minos.incremental;

/**
 * Raison structurée expliquant le mode d'indexation retenu.
 */
public enum IncrementalIndexingPlanReason {
    NO_CHANGES,
    INVALIDATION_REQUIRES_FULL,
    ALL_INDEXERS_SUPPORT_INCREMENTAL,
    INDEXER_INCREMENTAL_CAPABILITY_MISSING
}
