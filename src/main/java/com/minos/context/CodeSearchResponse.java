package com.minos.context;

import java.util.List;
import java.util.Objects;

/**
 * Réponse bornée d'une recherche de code M4.
 */
public record CodeSearchResponse(
        String projectId,
        String query,
        int maxDepth,
        int tokenBudget,
        int estimatedTokens,
        int estimatedTokensAvoided,
        boolean truncated,
        List<CodeContextResult> contexts
) {
    public CodeSearchResponse {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (maxDepth < 0 || maxDepth > CodeSearchCriteria.MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth is out of bounds");
        }
        if (tokenBudget < CodeSearchCriteria.MIN_TOKEN_BUDGET) {
            throw new IllegalArgumentException("tokenBudget is too small");
        }
        if (estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("estimatedTokens must fit the token budget");
        }
        if (estimatedTokensAvoided < 0) {
            throw new IllegalArgumentException("estimatedTokensAvoided must not be negative");
        }
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
    }

    public int count() {
        return contexts.size();
    }
}
