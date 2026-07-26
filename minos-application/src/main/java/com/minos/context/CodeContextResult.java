package com.minos.context;

import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;

import java.util.List;
import java.util.Objects;

/**
 * Bundle compact M4 centré sur un symbole racine.
 */
public record CodeContextResult(
        SymbolResult symbol,
        SourceExcerpt source,
        List<ContextRelationshipResult> relationships,
        List<UsageResult> usages,
        int estimatedTokens,
        boolean truncated
) {
    public CodeContextResult {
        Objects.requireNonNull(symbol, "symbol");
        relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
        usages = List.copyOf(Objects.requireNonNull(usages, "usages"));
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
    }
}
