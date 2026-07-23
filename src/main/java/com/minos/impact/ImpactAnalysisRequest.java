package com.minos.impact;

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
            throw new IllegalArgumentException("symbolId must not be blank");
        }
        if (maxDepth < 1 || maxDepth > 32) {
            throw new IllegalArgumentException("maxDepth must be between 1 and 32");
        }
        if (maxResults < 1 || maxResults > 10_000) {
            throw new IllegalArgumentException("maxResults must be between 1 and 10000");
        }
    }

    public static ImpactAnalysisRequest defaults(String symbolId) {
        return new ImpactAnalysisRequest(symbolId, 4, 200);
    }
}
