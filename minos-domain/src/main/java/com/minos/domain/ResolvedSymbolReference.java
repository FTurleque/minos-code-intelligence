package com.minos.domain;

/**
 * Référence vers un symbole connu du store MINOS.
 */
public record ResolvedSymbolReference(String symbolId) implements SymbolReference {

    public ResolvedSymbolReference {
        if (symbolId == null || symbolId.isBlank()) {
            throw new IllegalArgumentException("symbolId must not be blank");
        }
    }
}
