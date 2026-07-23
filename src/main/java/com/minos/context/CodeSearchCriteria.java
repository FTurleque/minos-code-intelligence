package com.minos.context;

import com.minos.domain.SymbolSearchCriteria;

import java.util.Objects;

/**
 * Critères M4 pour composer un contexte compact autour de symboles recherchés.
 */
public record CodeSearchCriteria(
        SymbolSearchCriteria symbols,
        int maxDepth,
        int usagesPerSymbol,
        int relationshipsPerNode,
        int contextLines,
        int maxTokens,
        boolean includeSource
) {
    public static final int MAX_DEPTH = 3;
    public static final int MAX_ITEMS_PER_NODE = 50;
    public static final int MAX_CONTEXT_LINES = 50;
    public static final int MIN_TOKEN_BUDGET = 256;
    public static final int MAX_TOKEN_BUDGET = 32_768;

    public CodeSearchCriteria {
        Objects.requireNonNull(symbols, "symbols");
        if (maxDepth < 0 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be between 0 and " + MAX_DEPTH);
        }
        validateCount(usagesPerSymbol, "usagesPerSymbol");
        validateCount(relationshipsPerNode, "relationshipsPerNode");
        if (contextLines < 0 || contextLines > MAX_CONTEXT_LINES) {
            throw new IllegalArgumentException(
                    "contextLines must be between 0 and " + MAX_CONTEXT_LINES);
        }
        if (maxTokens < MIN_TOKEN_BUDGET || maxTokens > MAX_TOKEN_BUDGET) {
            throw new IllegalArgumentException(
                    "maxTokens must be between " + MIN_TOKEN_BUDGET
                            + " and " + MAX_TOKEN_BUDGET);
        }
    }

    private static void validateCount(int value, String name) {
        if (value < 0 || value > MAX_ITEMS_PER_NODE) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and " + MAX_ITEMS_PER_NODE);
        }
    }
}
