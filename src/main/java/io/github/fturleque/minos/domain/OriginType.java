package io.github.fturleque.minos.domain;

/**
 * Origine technique ou analytique d'un fait MINOS.
 */
public enum OriginType {
    SCIP,
    GLEAN,
    LSP,
    COMPILER,
    AST,
    CPG,
    DERIVED_BY_MINOS,
    HEURISTIC_BY_MINOS,
    OTHER
}
