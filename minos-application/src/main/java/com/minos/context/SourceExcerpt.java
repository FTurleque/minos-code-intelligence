package com.minos.context;

/**
 * Contenu source local retourné par M4, sous forme de plage ou de fichier
 * complet explicitement demandé.
 */
public record SourceExcerpt(
        String fileId,
        int startLine,
        int endLine,
        String content,
        boolean fullFile,
        boolean truncated,
        int estimatedTokens,
        int totalFileLines,
        int totalFileTokens
) {
    public SourceExcerpt {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid source line range");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (estimatedTokens < 0 || totalFileLines < 0 || totalFileTokens < 0) {
            throw new IllegalArgumentException("source metrics must not be negative");
        }
        if (estimatedTokens > totalFileTokens && !content.isEmpty()) {
            throw new IllegalArgumentException("excerpt tokens exceed full file tokens");
        }
    }

    public int estimatedTokensAvoided() {
        return Math.max(0, totalFileTokens - estimatedTokens);
    }
}
