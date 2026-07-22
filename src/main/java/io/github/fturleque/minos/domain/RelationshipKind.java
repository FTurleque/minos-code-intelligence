package io.github.fturleque.minos.domain;

/**
 * Relations factuelles et dérivées communes à MINOS.
 */
public enum RelationshipKind {
    DECLARES,
    CONTAINS,
    IMPORTS,
    REFERENCES,
    EXTENDS,
    IMPLEMENTS,
    CALLS,
    RETURNS,
    ACCEPTS,
    READS,
    WRITES,
    INSTANTIATES,
    DEPENDS_ON,
    INJECTS,
    RELATED_TEST,
    IMPACT_PATH,
    ARCHITECTURAL_ROLE,
    CENTRALITY,
    OTHER
}
