package io.github.fturleque.minos.domain;

/**
 * Qualité de la clé logique attribuée à un symbole MINOS.
 */
public enum SymbolIdentityQuality {
    /** Identité canonique reconstruite indépendamment du fournisseur. */
    CANONICAL,

    /** Identité déterministe dérivée de la structure, signature et localisation connues. */
    STRUCTURAL_FALLBACK,

    /** Repli dépendant d'une identité externe, à ne pas utiliser pour réconcilier des fournisseurs. */
    PROVIDER_SCOPED_FALLBACK
}
