package com.minos.domain;

/**
 * Relations factuelles et dérivées communes à MINOS.
 */
public enum RelationshipKind {
    DECLARES,
    DEFINITION,
    TYPE_DEFINITION,
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
