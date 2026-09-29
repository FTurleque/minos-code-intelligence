package com.minos.dynamic;

import java.util.List;
import java.util.Objects;

/** Persisted correlation evidence for one source or target runtime reference. */
public record RuntimeSymbolResolution(
        RuntimeResolutionStatus status,
        RuntimeSymbolReference reference,
        String symbolId,
        String symbolKey,
        String qualifiedName,
        List<String> candidateSymbolIds,
        boolean candidatesTruncated
) {
    public static final int MAX_CANDIDATE_SYMBOL_IDS = 1_000;

    public RuntimeSymbolResolution {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reference, "reference");
        candidateSymbolIds = List.copyOf(Objects.requireNonNull(candidateSymbolIds, "candidateSymbolIds"));
        if (candidateSymbolIds.size() > MAX_CANDIDATE_SYMBOL_IDS
                || candidateSymbolIds.stream().anyMatch(value -> value == null || value.isBlank())
                || candidateSymbolIds.stream().distinct().count() != candidateSymbolIds.size()) {
            throw new IllegalArgumentException("candidate symbol identities are invalid or exceed their limit");
        }
        if (status == RuntimeResolutionStatus.RESOLVED) {
            if (symbolId == null || symbolId.isBlank() || symbolKey == null || symbolKey.isBlank()) {
                throw new IllegalArgumentException("resolved runtime symbol requires static identity");
            }
            if (!candidateSymbolIds.isEmpty() || candidatesTruncated) throw new IllegalArgumentException("resolved symbol must not expose candidates");
        } else if (symbolId != null || symbolKey != null || qualifiedName != null) {
            throw new IllegalArgumentException("unresolved or ambiguous symbol must not expose a selected identity");
        }
        if (status == RuntimeResolutionStatus.AMBIGUOUS && candidateSymbolIds.size() < 2) {
            throw new IllegalArgumentException("ambiguous symbol requires at least two candidates");
        }
        if (status != RuntimeResolutionStatus.AMBIGUOUS && candidatesTruncated) {
            throw new IllegalArgumentException("only ambiguous resolution can truncate candidates");
        }
        if (status == RuntimeResolutionStatus.UNRESOLVED && (!candidateSymbolIds.isEmpty() || candidatesTruncated)) {
            throw new IllegalArgumentException("unresolved symbol must not expose candidates");
        }
    }
}
