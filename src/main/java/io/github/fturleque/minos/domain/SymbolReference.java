package io.github.fturleque.minos.domain;

/**
 * Référence d'une occurrence vers un symbole résolu ou une cible non résolue.
 */
public sealed interface SymbolReference
        permits ResolvedSymbolReference, UnresolvedSymbolReference {
}
