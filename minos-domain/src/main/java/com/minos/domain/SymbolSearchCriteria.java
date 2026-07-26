package com.minos.domain;

/**
 * Critères structurés et indépendants du backend pour rechercher des symboles.
 *
 * <p>Le texte est une recherche lexicale insensible à la casse. Le nom qualifié,
 * lorsqu'il est fourni, est un filtre exact. Les filtres peuvent être combinés.</p>
 */
public record SymbolSearchCriteria(
        String text,
        String qualifiedName,
        SymbolKind kind,
        String moduleId,
        int limit) {

    public SymbolSearchCriteria {
        text = blankToNull(text);
        qualifiedName = blankToNull(qualifiedName);
        moduleId = blankToNull(moduleId);

        if (text == null && qualifiedName == null && kind == null && moduleId == null) {
            throw new IllegalArgumentException("at least one symbol search criterion is required");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
    }

    public static SymbolSearchCriteria lexical(String text, int limit) {
        return new SymbolSearchCriteria(text, null, null, null, limit);
    }

    public static SymbolSearchCriteria qualifiedName(String qualifiedName, int limit) {
        return new SymbolSearchCriteria(null, qualifiedName, null, null, limit);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
