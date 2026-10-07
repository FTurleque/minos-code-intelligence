package com.minos.orchestration;

/**
 * Observable capability that a provider can explicitly qualify.
 *
 * <p>M17 capability model v2 deliberately separates facts which were previously
 * bundled under broad indexing support. A capability is never inferred from a
 * provider being able to start.</p>
 */
public enum IndexerCapability {
    SYMBOLS,
    STABLE_SYMBOL_IDENTITY,
    REFERENCES,
    UNRESOLVED_REFERENCES,
    IMPLEMENTATION_RELATIONS,
    STRUCTURAL_RELATIONS,
    CALL_RELATIONS,
    MULTI_MODULE,
    TEST_SOURCES,
    PARTIAL_INDEX_ON_BUILD_FAILURE,
    INCREMENTAL_INDEXING,
    POSITION_UTF16,
    RUNTIME_INSTALLATION,
    /**
     * The provider's final artifact depends only on the sources and build descriptors of its scope
     * (covered by the scope fingerprint), so an artifact left by an interrupted run may be reused by a
     * resume (ADR 0039 §3). Never inferred: declared per provider after a reproducibility review.
     */
    RESUMABLE_ARTIFACT
}
