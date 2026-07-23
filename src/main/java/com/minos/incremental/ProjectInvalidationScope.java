package com.minos.incremental;

/**
 * Portée conservatrice d'invalidation avant toute négociation de capacité fournisseur.
 */
public enum ProjectInvalidationScope {
    NONE,
    PARTIAL_CANDIDATE,
    FULL_REQUIRED
}
