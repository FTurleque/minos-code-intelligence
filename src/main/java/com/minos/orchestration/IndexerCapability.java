package com.minos.orchestration;

/**
 * Capacité observable qu'un indexeur peut annoncer à MINOS.
 *
 * <p>Une capacité signifie que le fournisseur a été qualifié pour produire ce
 * type d'information ou supporter ce scénario. Elle ne constitue jamais une
 * promesse de complétude sémantique.</p>
 */
public enum IndexerCapability {
    SYMBOLS,
    REFERENCES,
    IMPLEMENTATION_RELATIONS,
    STRUCTURAL_RELATIONS,
    MULTI_MODULE,
    TEST_SOURCES,
    PARTIAL_INDEX_ON_BUILD_FAILURE,
    INCREMENTAL_INDEXING
}
