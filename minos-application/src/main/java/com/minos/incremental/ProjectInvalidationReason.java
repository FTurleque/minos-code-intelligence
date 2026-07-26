package com.minos.incremental;

/**
 * Motif explicable ayant conduit à une portée d'invalidation M7.
 */
public enum ProjectInvalidationReason {
    NO_ACTIVE_INDEX,
    MISSING_FINGERPRINT_BASELINE,
    FINGERPRINT_BASELINE_UNREADABLE,
    BASELINE_INDEX_MISMATCH,
    BUILD_DEFINITION_CHANGED,
    IGNORE_POLICY_CHANGED,
    UNQUALIFIED_FILE_CHANGE,
    SOURCE_OR_TEST_CHANGED
}
