package io.github.fturleque.minos.domain;

/**
 * Rôle sémantique d'une occurrence de symbole dans le code source.
 *
 * <p>Une occurrence peut cumuler plusieurs rôles. Par exemple, un fournisseur
 * peut signaler une occurrence comme IMPORT + READ ou DEFINITION + TEST.</p>
 */
public enum OccurrenceRole {
    DEFINITION,
    FORWARD_DEFINITION,
    REFERENCE,
    IMPORT,
    CALL,
    IMPLEMENTATION,
    INHERITANCE,
    READ,
    WRITE,
    GENERATED,
    TEST,
    INSTANTIATION,
    OTHER
}
