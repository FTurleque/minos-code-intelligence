package io.github.fturleque.minos.domain;

/**
 * Rôle d'une occurrence de symbole dans le code source.
 */
public enum OccurrenceRole {
    DEFINITION,
    REFERENCE,
    IMPORT,
    CALL,
    IMPLEMENTATION,
    INHERITANCE,
    READ,
    WRITE,
    INSTANTIATION,
    OTHER
}
