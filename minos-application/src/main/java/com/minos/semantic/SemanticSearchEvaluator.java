package com.minos.semantic;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Controlled relevance metrics used by M20 promotion gates. */
public final class SemanticSearchEvaluator {

    public Evaluation evaluate(List<String> rankedStableKeys, Set<String> relevantStableKeys, int k) {
        Objects.requireNonNull(rankedStableKeys, "rankedStableKeys");
        Set<String> relevant = Set.copyOf(Objects.requireNonNull(relevantStableKeys, "relevantStableKeys"));
        if (relevant.isEmpty()) throw new IllegalArgumentException("relevantStableKeys must not be empty");
        if (k < 1) throw new IllegalArgumentException("k must be greater than zero");
        List<String> top = rankedStableKeys.stream().limit(k).toList();
        int relevantRetrieved = 0;
        Set<String> seen = new HashSet<>();
        for (String key : top) {
            if (seen.add(key) && relevant.contains(key)) relevantRetrieved++;
        }
        double recallAtK = relevantRetrieved / (double) relevant.size();
        double reciprocalRank = 0.0;
        for (int i = 0; i < rankedStableKeys.size(); i++) {
            if (relevant.contains(rankedStableKeys.get(i))) {
                reciprocalRank = 1.0 / (i + 1.0);
                break;
            }
        }
        double dcg = 0.0;
        for (int i = 0; i < top.size(); i++) {
            if (relevant.contains(top.get(i))) dcg += 1.0 / log2(i + 2.0);
        }
        double idealDcg = 0.0;
        int ideal = Math.min(k, relevant.size());
        for (int i = 0; i < ideal; i++) idealDcg += 1.0 / log2(i + 2.0);
        double ndcgAtK = idealDcg == 0.0 ? 0.0 : dcg / idealDcg;
        return new Evaluation(k, relevant.size(), relevantRetrieved, recallAtK, reciprocalRank, ndcgAtK);
    }

    public Gain compare(Evaluation lexical, Evaluation hybrid) {
        Objects.requireNonNull(lexical, "lexical");
        Objects.requireNonNull(hybrid, "hybrid");
        if (lexical.k() != hybrid.k()) throw new IllegalArgumentException("evaluations must use the same k");
        return new Gain(
                hybrid.recallAtK() - lexical.recallAtK(),
                hybrid.mrr() - lexical.mrr(),
                hybrid.ndcgAtK() - lexical.ndcgAtK());
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    public record Evaluation(
            int k,
            int relevantCount,
            int relevantRetrieved,
            double recallAtK,
            double mrr,
            double ndcgAtK
    ) {
    }

    public record Gain(double recallGain, double mrrGain, double ndcgGain) {
        public boolean measurableGain() {
            return recallGain > 0.0 || mrrGain > 0.0 || ndcgGain > 0.0;
        }
    }
}
