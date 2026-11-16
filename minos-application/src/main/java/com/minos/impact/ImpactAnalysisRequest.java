package com.minos.impact;

import com.minos.diagnostics.UsageException;

/**
 * Paramètres bornés d'une analyse d'impact à partir d'un symbole racine.
 */
public record ImpactAnalysisRequest(
        String symbolId,
        int maxDepth,
        int maxResults
) {
    public ImpactAnalysisRequest {
        if (symbolId == null || symbolId.isBlank()) {
            throw new UsageException("symbolId must not be blank");
        }
        if (maxDepth < 1 || maxDepth > 32) {
            throw new UsageException("maxDepth must be between 1 and 32");
        }
        if (maxResults < 1 || maxResults > 10_000) {
            throw new UsageException("maxResults must be between 1 and 10000");
        }
    }

    public static ImpactAnalysisRequest defaults(String symbolId) {
        return new ImpactAnalysisRequest(symbolId, 4, 200);
    }
}
